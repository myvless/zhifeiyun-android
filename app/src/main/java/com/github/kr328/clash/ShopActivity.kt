package com.github.kr328.clash

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.zhifeiyun.AccountStore
import com.github.kr328.clash.zhifeiyun.PanelClient
import com.github.kr328.clash.zhifeiyun.PanelConfig
import com.github.kr328.clash.zhifeiyun.ShopClient
import com.github.kr328.clash.zhifeiyun.ShopParser
import com.github.kr328.clash.zhifeiyun.ShopParser.PayKind
import com.github.kr328.clash.zhifeiyun.Ui
import com.github.kr328.clash.zhifeiyun.Ui.dp
import com.github.kr328.clash.zhifeiyun.Ui.outlineButton
import com.github.kr328.clash.zhifeiyun.Ui.primaryButton
import com.github.kr328.clash.zhifeiyun.Ui.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Native 续费 flow: 选择套餐 → 确认订单（优惠码）→ 账单 → 选择支付方式 → open the gateway.
 * Uses the saved panel session cookie; any step that cannot be parsed opens in [PanelWebActivity].
 */
class ShopActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private enum class Step { Products, Confirm, Invoice }

    private lateinit var shop: ShopClient
    private lateinit var titleView: TextView
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var footer: LinearLayout

    private var step = Step.Products
    private var products: List<ShopParser.Product>? = null
    private var product: ShopParser.Product? = null
    private var preview: ShopParser.OrderPreview? = null
    private var invoice: ShopParser.Invoice? = null
    private var invoiceId: String? = null
    private var option: ShopParser.PayOption? = null
    private var awaitingPayment = false
    private var webFallbackStep: Step? = null

    private val webLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        shop.reloadCookies()
        when (webFallbackStep) {
            Step.Invoice -> invoiceId?.let { loadInvoice(it) }
            null -> Unit
            else -> { setResult(RESULT_OK); finish() }
        }
        webFallbackStep = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        shop = ShopClient(this)
        setContentView(buildChrome())
        loadProducts()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingPayment && step == Step.Invoice) {
            awaitingPayment = false
            invoiceId?.let { loadInvoice(it, quiet = true) }
        }
    }

    // ------------------------------------------------------------------ chrome

    private fun buildChrome(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.GREY_BG)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(12), dp(4))
        }
        bar.addView(text("‹", 28f, Ui.BLUE).apply {
            setPadding(dp(10), 0, dp(14), dp(4))
            setOnClickListener { onBackPressed() }
        })
        titleView = text(getString(R.string.zfy_shop_title), 17f, Ui.TEXT, true)
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(text(getString(R.string.zfy_open_web), 14f, Ui.BLUE).apply {
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { openWeb(currentWebUrl(), null) }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(12))
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun currentWebUrl(): String = when (step) {
        Step.Products -> PanelConfig.SHOP_URL
        Step.Confirm -> product?.let { PanelConfig.BASE_URL + ShopClient.orderCreatePath(it.id) } ?: PanelConfig.SHOP_URL
        Step.Invoice -> invoiceId?.let { PanelConfig.BASE_URL + ShopClient.invoicePath(it) } ?: PanelConfig.SHOP_URL
    }

    private fun showLoading(msg: String) {
        body.removeAllViews()
        footer.visibility = View.GONE
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(80), 0, 0)
        }
        box.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(40), dp(40)))
        box.addView(text(msg, 14f, Ui.TEXT_SUB).apply { setPadding(0, dp(12), 0, 0) })
        body.addView(box, matchWrap())
    }

    private fun showError(msg: String, retry: () -> Unit) {
        body.removeAllViews()
        footer.visibility = View.GONE
        val c = card()
        c.addView(text(msg, 15f, Ui.TEXT).apply { gravity = Gravity.CENTER })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(outlineButton(getString(R.string.zfy_open_web)).apply {
            setOnClickListener { openWeb(currentWebUrl(), step) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        row.addView(primaryButton(getString(R.string.zfy_retry)).apply {
            setOnClickListener { retry() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        c.addView(row, matchWrap().apply { topMargin = dp(16) })
        body.addView(c, matchWrap().apply { topMargin = dp(40) })
    }

    // ------------------------------------------------------------------ runner

    /**
     * Runs [block] on IO. Session expiry → re-login prompt then retry; unparseable → WebView
     * fallback for [step]; other errors → [onError] (default: error card with retry).
     */
    private fun <T> work(
        retry: () -> Unit,
        block: () -> T,
        onError: ((String) -> Unit)? = null,
        onOk: (T) -> Unit,
    ) {
        launch {
            try {
                val r = withContext(Dispatchers.IO) { block() }
                onOk(r)
            } catch (e: ShopClient.SessionExpired) {
                if (relogin()) retry() else {
                    val msg = getString(R.string.zfy_session_expired)
                    onError?.invoke(msg) ?: showError(msg, retry)
                }
            } catch (e: ShopClient.ParseFailed) {
                openWeb(e.webUrl, step)
            } catch (e: IllegalStateException) {
                val msg = e.message ?: getString(R.string.zfy_network_error)
                onError?.invoke(msg) ?: showError(msg, retry)
            } catch (e: Exception) {
                val msg = getString(R.string.zfy_network_error)
                onError?.invoke(msg) ?: showError(msg, retry)
            }
        }
    }

    private fun openWeb(url: String, fallbackFor: Step?) {
        webFallbackStep = fallbackFor
        if (fallbackFor != null) {
            Toast.makeText(this, R.string.zfy_web_fallback, Toast.LENGTH_SHORT).show()
        }
        PanelWebActivity.injectCookies(this)
        webLauncher.launch(PanelWebActivity.intent(this, url, titleView.text.toString()))
    }

    // ------------------------------------------------------------------ step 1: products

    private fun loadProducts() {
        step = Step.Products
        titleView.text = getString(R.string.zfy_shop_title)
        val cached = products
        if (cached != null) { renderProducts(cached); return }
        showLoading(getString(R.string.zfy_loading_plans))
        work(::loadProducts, { shop.products() }) {
            products = it
            renderProducts(it)
        }
    }

    private fun renderProducts(list: List<ShopParser.Product>) {
        body.removeAllViews()
        footer.visibility = View.GONE
        if (list.isEmpty()) {
            val c = card()
            c.addView(text(getString(R.string.zfy_no_plans), 15f, Ui.TEXT).apply { gravity = Gravity.CENTER })
            c.addView(outlineButton(getString(R.string.zfy_open_web)).apply {
                setOnClickListener { openWeb(PanelConfig.SHOP_URL, Step.Products) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
            body.addView(c, matchWrap().apply { topMargin = dp(24) })
            return
        }
        body.addView(text(getString(R.string.zfy_pick_plan_hint), 13f, Ui.TEXT_SUB).apply {
            setPadding(dp(4), dp(4), 0, dp(4))
        })
        var lastCat: String? = "\u0000"
        list.forEach { p ->
            if (p.category != lastCat) {
                lastCat = p.category
                if (p.category != null) {
                    body.addView(text(p.category, 14f, Ui.TEXT_SUB, true).apply {
                        setPadding(dp(4), dp(14), 0, dp(2))
                    })
                }
            }
            body.addView(productCard(p), matchWrap().apply { topMargin = dp(10) })
        }
    }

    private fun productCard(p: ShopParser.Product): View {
        val c = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(p.name, 16f, Ui.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text(p.price?.let { "¥$it" } ?: "", 19f, Ui.BLUE, true).apply {
            setPadding(dp(8), 0, 0, 0)
        })
        c.addView(top)
        val featureLine = p.features.joinToString("  ·  ") { (k, v) -> "$k $v" }
        if (featureLine.isNotEmpty()) {
            c.addView(text(featureLine, 13f, Ui.TEXT_SUB).apply {
                setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.15f)
            })
        }
        if (!p.description.isNullOrBlank()) {
            c.addView(text(p.description, 13f, Ui.TEXT_SUB).apply { setPadding(0, dp(4), 0, 0) })
        }
        if (p.available) {
            c.setOnClickListener {
                product = p
                preview = null
                loadConfirm()
            }
        } else {
            c.alpha = 0.5f
            c.addView(text(getString(R.string.zfy_sold_out), 12f, 0xFFEF4444.toInt(), true).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        return c
    }

    // ------------------------------------------------------------------ step 2: confirm + coupon

    private fun loadConfirm() {
        val p = product ?: return loadProducts()
        step = Step.Confirm
        titleView.text = getString(R.string.zfy_confirm_order)
        showLoading(getString(R.string.zfy_loading))
        work(::loadConfirm, { shop.orderPreview(p.id) }) {
            preview = it
            renderConfirm(p, it)
        }
    }

    private fun renderConfirm(p: ShopParser.Product, pv: ShopParser.OrderPreview?) {
        body.removeAllViews()

        val info = card()
        info.addView(text(p.name, 17f, Ui.TEXT, true))
        p.category?.let { info.addView(text(it, 12f, Ui.TEXT_SUB).apply { setPadding(0, dp(2), 0, dp(4)) }) }
        val rows = pv?.details?.filter { it.first != "商品名称" }?.takeIf { it.isNotEmpty() } ?: p.features
        rows.forEach { (k, v) -> info.addView(kv(k, v)) }
        if (!p.description.isNullOrBlank()) info.addView(text(p.description, 13f, Ui.TEXT_SUB).apply { setPadding(0, dp(6), 0, 0) })
        body.addView(info, matchWrap())

        // Coupon
        val couponCard = card()
        couponCard.addView(text(getString(R.string.zfy_coupon), 14f, Ui.TEXT, true))
        val couponRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val couponInput = EditText(this).apply {
            hint = getString(R.string.zfy_coupon_hint)
            setSingleLine(true)
            textSize = 15f
            setTextColor(Ui.TEXT)
            setHintTextColor(0xFFAAB4C0.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.rounded(Ui.GREY_BG, dp(10).toFloat())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val couponStatus = text("", 13f, Ui.TEXT_SUB).apply { visibility = View.GONE; setPadding(0, dp(6), 0, 0) }
        val applyBtn = outlineButton(getString(R.string.zfy_apply))
        couponRow.addView(couponInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        couponRow.addView(applyBtn, LinearLayout.LayoutParams(dp(76), dp(44)).apply { leftMargin = dp(8) })
        couponCard.addView(couponRow, matchWrap().apply { topMargin = dp(8) })
        couponCard.addView(couponStatus)
        body.addView(couponCard, matchWrap().apply { topMargin = dp(12) })

        // Price
        val basePrice = pv?.price ?: p.price
        val priceCard = card()
        priceCard.addView(kv(getString(R.string.zfy_price), basePrice?.let { "¥$it" } ?: "—"))
        val discountRow = kv(getString(R.string.zfy_discount), "").apply { visibility = View.GONE }
        priceCard.addView(discountRow)
        val totalRow = kv(getString(R.string.zfy_total), basePrice?.let { "¥$it" } ?: "—", bold = true)
        priceCard.addView(totalRow)
        body.addView(priceCard, matchWrap().apply { topMargin = dp(12) })

        applyBtn.setOnClickListener {
            val code = couponInput.text.toString().trim()
            if (code.isEmpty()) return@setOnClickListener
            applyBtn.isEnabled = false
            work(::loadConfirm, { shop.checkCoupon(p.id, code) }, onError = { msg ->
                applyBtn.isEnabled = true
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }) { r ->
                applyBtn.isEnabled = true
                couponStatus.visibility = View.VISIBLE
                couponStatus.text = r.message
                couponStatus.setTextColor(if (r.ok) Ui.GREEN else 0xFFEF4444.toInt())
                if (r.ok) {
                    discountRow.visibility = View.VISIBLE
                    (discountRow.getChildAt(1) as TextView).text = "-¥" + (r.discount ?: "0")
                    (totalRow.getChildAt(1) as TextView).text = "¥" + (r.total ?: basePrice ?: "—")
                } else {
                    discountRow.visibility = View.GONE
                    (totalRow.getChildAt(1) as TextView).text = basePrice?.let { "¥$it" } ?: "—"
                }
            }
        }

        // Footer
        footer.removeAllViews()
        footer.visibility = View.VISIBLE
        val create = primaryButton(getString(R.string.zfy_create_order))
        create.setOnClickListener {
            create.isEnabled = false
            create.text = getString(R.string.zfy_processing)
            val code = couponInput.text.toString().trim()
            work({ create.isEnabled = true; create.text = getString(R.string.zfy_create_order) },
                { shop.createOrder(p.id, code) },
                onError = { msg ->
                    create.isEnabled = true
                    create.text = getString(R.string.zfy_create_order)
                    AlertDialog.Builder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
                }) { id ->
                setResult(RESULT_OK)
                invoiceId = id
                option = null
                loadInvoice(id)
            }
        }
        footer.addView(create, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        scroll.scrollTo(0, 0)
    }

    // ------------------------------------------------------------------ step 3: invoice + payment

    private fun loadInvoice(id: String, quiet: Boolean = false) {
        step = Step.Invoice
        titleView.text = getString(R.string.zfy_invoice_title)
        if (!quiet || invoice == null) showLoading(getString(R.string.zfy_loading))
        work({ loadInvoice(id) }, { shop.invoice(id) }) {
            val wasUnpaid = invoice?.unpaid == true
            invoice = it
            renderInvoice(it)
            if (wasUnpaid && !it.unpaid) {
                setResult(RESULT_OK)
                Toast.makeText(this, R.string.zfy_paid_toast, Toast.LENGTH_LONG).show()
            } else if (quiet && it.unpaid) {
                Toast.makeText(this, R.string.zfy_not_paid_yet, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderInvoice(inv: ShopParser.Invoice) {
        body.removeAllViews()

        val head = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(getString(R.string.zfy_invoice_no, inv.id), 15f, Ui.TEXT_SUB),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inv.status?.let { s ->
            top.addView(text(s, 12f, if (inv.unpaid) 0xFFF59E0B.toInt() else Ui.GREEN, true).apply {
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = Ui.rounded(if (inv.unpaid) 0x1FF59E0B else 0x1F22B573, dp(10).toFloat())
            })
        }
        head.addView(top)
        head.addView(text(inv.price?.let { "¥$it" } ?: "—", 30f, Ui.TEXT, true).apply { setPadding(0, dp(8), 0, 0) })
        head.addView(text(getString(R.string.zfy_amount_due), 12f, Ui.TEXT_SUB))
        body.addView(head, matchWrap())

        if (inv.items.isNotEmpty() || inv.info.isNotEmpty()) {
            val detail = card()
            inv.items.forEach { (k, v) -> detail.addView(kv(k, v)) }
            inv.info.forEach { (k, v) -> detail.addView(kv(k, v, small = true)) }
            body.addView(detail, matchWrap().apply { topMargin = dp(12) })
        }

        footer.removeAllViews()
        footer.visibility = View.VISIBLE

        if (!inv.unpaid) {
            val done = card()
            done.addView(text(getString(R.string.zfy_paid_done), 15f, Ui.GREEN, true).apply { gravity = Gravity.CENTER })
            body.addView(done, matchWrap().apply { topMargin = dp(12) })
            footer.addView(primaryButton(getString(R.string.zfy_done)).apply {
                setOnClickListener { setResult(RESULT_OK); finish() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            return
        }

        body.addView(text(getString(R.string.zfy_pay_method), 14f, Ui.TEXT_SUB, true).apply {
            setPadding(dp(4), dp(16), 0, dp(6))
        })
        if (inv.options.isEmpty()) {
            val c = card()
            c.addView(text(getString(R.string.zfy_no_pay_method), 14f, Ui.TEXT))
            body.addView(c, matchWrap())
        } else {
            if (option == null || inv.options.none { it.key == option?.key }) {
                option = inv.options.firstOrNull { it.kind != PayKind.Balance } ?: inv.options.first()
            }
            val listCard = card().apply { setPadding(0, dp(4), 0, dp(4)) }
            val radios = ArrayList<Pair<ShopParser.PayOption, View>>()
            inv.options.forEachIndexed { i, o ->
                if (i > 0) listCard.addView(View(this).apply { setBackgroundColor(0xFFEDF1F6.toInt()) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(48) })
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                }
                val dot = View(this)
                row.addView(dot, LinearLayout.LayoutParams(dp(18), dp(18)))
                val label = if (o.kind == PayKind.Balance && inv.balance != null)
                    getString(R.string.zfy_pay_balance, inv.balance) else o.label
                row.addView(text(label, 15f, Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                })
                radios += o to dot
                row.setOnClickListener {
                    option = o
                    radios.forEach { (opt, v) -> v.background = radioDrawable(opt.key == o.key) }
                }
                listCard.addView(row)
            }
            radios.forEach { (opt, v) -> v.background = radioDrawable(opt.key == option?.key) }
            body.addView(listCard, matchWrap())
        }

        val pay = primaryButton(getString(R.string.zfy_go_pay, inv.price ?: ""))
        pay.isEnabled = inv.options.isNotEmpty()
        pay.setOnClickListener { option?.let { startPay(inv, it, pay) } }
        footer.addView(pay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        footer.addView(text(getString(R.string.zfy_paid_refresh), 14f, Ui.BLUE).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { loadInvoice(inv.id, quiet = true) }
        }, matchWrap())
        scroll.scrollTo(0, 0)
    }

    private fun startPay(inv: ShopParser.Invoice, o: ShopParser.PayOption, btn: TextView) {
        when (o.kind) {
            PayKind.WebOnly -> {
                openWeb(PanelConfig.BASE_URL + ShopClient.invoicePath(inv.id), Step.Invoice); return
            }
            PayKind.Balance -> {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.zfy_pay_balance_confirm, inv.price ?: ""))
                    .setPositiveButton(android.R.string.ok) { _, _ -> doPay(inv, o, btn) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
            else -> doPay(inv, o, btn)
        }
    }

    private fun doPay(inv: ShopParser.Invoice, o: ShopParser.PayOption, btn: TextView) {
        val label = btn.text
        btn.isEnabled = false
        btn.text = getString(R.string.zfy_processing)
        val restore = { btn.isEnabled = true; btn.text = label }
        work({ restore(); loadInvoice(inv.id) }, { shop.pay(inv.id, o, inv.price) }, onError = { msg ->
            restore()
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }) { r ->
            restore()
            when (r) {
                is ShopParser.ActionResult.Redirect -> {
                    val url = ShopClient.absolute(r.url)
                    val host = Uri.parse(url).host ?: ""
                    if (host.equals(Uri.parse(PanelConfig.BASE_URL).host, true)) {
                        // Panel page (e.g. balance payment done → /user/invoice): just re-read the invoice.
                        loadInvoice(inv.id, quiet = true)
                    } else {
                        awaitingPayment = true
                        PanelWebActivity.openExternal(this, url)
                    }
                }
                ShopParser.ActionResult.Refresh -> loadInvoice(inv.id)
                is ShopParser.ActionResult.Qr -> {
                    awaitingPayment = true
                    openQr(r.content)
                }
                is ShopParser.ActionResult.Message ->
                    if (r.ok) loadInvoice(inv.id, quiet = true)
                    else Toast.makeText(this, r.message.ifBlank { getString(R.string.zfy_pay_failed) }, Toast.LENGTH_LONG).show()
                ShopParser.ActionResult.Unknown ->
                    openWeb(PanelConfig.BASE_URL + ShopClient.invoicePath(inv.id), Step.Invoice)
            }
        }
    }

    /** Alipay QR payloads (https://qr.alipay.com/…): open in the Alipay app, else in the browser. */
    private fun openQr(content: String) {
        if (content.contains("alipay", true)) {
            val scheme = "alipays://platformapi/startapp?saId=10000007&qrcode=" + URLEncoder.encode(content, "UTF-8")
            val ok = runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(scheme)))
            }.isSuccess
            if (ok) return
        }
        if (content.startsWith("http") || content.contains("://")) {
            PanelWebActivity.openExternal(this, content)
        } else {
            AlertDialog.Builder(this).setMessage(content).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    // ------------------------------------------------------------------ re-login

    private suspend fun relogin(): Boolean {
        val store = AccountStore(this)
        val email = store.email ?: return false
        while (true) {
            val password = ask(getString(R.string.zfy_relogin_title),
                getString(R.string.zfy_relogin_msg, email), getString(R.string.zfy_password_hint), true)
                ?: return false
            val client = PanelClient()
            val result = runCatching { withContext(Dispatchers.IO) { client.login(email, password) } }
                .getOrElse { PanelClient.LoginResult.Failed(getString(R.string.zfy_network_error)) }
            var final = result
            if (result is PanelClient.LoginResult.NeedMfa) {
                val code = ask(getString(R.string.zfy_totp_title), null, getString(R.string.zfy_totp_hint), false)
                    ?: return false
                final = runCatching { withContext(Dispatchers.IO) { client.submitTotp(code) } }
                    .getOrElse { PanelClient.LoginResult.Failed(getString(R.string.zfy_network_error)) }
            }
            if (final is PanelClient.LoginResult.Failed) {
                Toast.makeText(this, final.message, Toast.LENGTH_LONG).show()
                continue
            }
            store.cookies = client.cookieMap
            shop.reloadCookies()
            return true
        }
    }

    private suspend fun ask(title: String, message: String?, hint: String, password: Boolean): String? =
        suspendCancellableCoroutine { cont ->
            val input = EditText(this).apply {
                this.hint = hint
                setSingleLine(true)
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_NUMBER
            }
            val box = LinearLayout(this).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            var done = false
            val b = AlertDialog.Builder(this).setTitle(title).setView(box)
            if (message != null) b.setMessage(message)
            b.setPositiveButton(android.R.string.ok) { _, _ ->
                if (!done) { done = true; cont.resume(input.text.toString().ifBlank { null }) }
            }
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (!done) { done = true; cont.resume(null) } }
                .setOnCancelListener { if (!done) { done = true; cont.resume(null) } }
                .show()
        }

    // ------------------------------------------------------------------ helpers

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Color.WHITE, dp(14).toFloat())
        setPadding(dp(18), dp(16), dp(18), dp(16))
        elevation = dp(1).toFloat()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun kv(k: String, v: String, bold: Boolean = false, small: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(if (small) 4 else 7), 0, dp(if (small) 4 else 7))
            val size = if (small) 12f else 14f
            addView(text(k, size, Ui.TEXT_SUB), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(v, if (bold) 17f else size, if (bold) Ui.BLUE else Ui.TEXT, bold).apply {
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        }

    private fun radioDrawable(on: Boolean): android.graphics.drawable.Drawable {
        val ring = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), if (on) Ui.BLUE else 0xFFC3CBD6.toInt())
        }
        if (!on) return ring
        val dot = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Ui.BLUE)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(ring, dot)).apply {
            setLayerInset(1, dp(4), dp(4), dp(4), dp(4))
        }
    }

    override fun onBackPressed() {
        when (step) {
            Step.Products -> finish()
            Step.Confirm -> loadProducts()
            Step.Invoice -> loadProducts()
        }
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }
}
