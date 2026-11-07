package com.github.kr328.clash

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import com.github.kr328.clash.zhifeiyun.Tv
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
    private lateinit var footerBox: android.widget.FrameLayout

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
        Ui.applySystemBars(this, Ui.SURFACE)
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
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(8), dp(4), dp(12), dp(4))
        }
        bar.addView(text("‹", 28f, Ui.BLUE).apply {
            setPadding(dp(14), 0, dp(14), dp(4))
            setOnClickListener { onBackPressed() }
            Tv.focusRing(this, 10f, scale = 1.08f)
        })
        titleView = text(getString(R.string.zfy_shop_title), 17f, Ui.TEXT, true)
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(text(getString(R.string.zfy_open_web), 14f, Ui.BLUE).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { openWeb(currentWebUrl(), null) }
            Tv.focusRing(this, 10f, scale = 1.06f)
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        // Content limited to ~600dp and centred on wide screens (TV / tablet / landscape).
        body = Tv.maxWidthColumn(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body, Tv.centeredInFrame())
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footer = Tv.maxWidthColumn(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        footerBox = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Ui.SURFACE)
            elevation = dp(4).toFloat()
            visibility = View.GONE
            addView(footer, Tv.centeredInFrame())
        }
        root.addView(footerBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun currentWebUrl(): String = when (step) {
        Step.Products -> PanelConfig.SHOP_URL
        Step.Confirm -> product?.let { PanelConfig.BASE_URL + ShopClient.orderCreatePath(it.id) } ?: PanelConfig.SHOP_URL
        Step.Invoice -> invoiceId?.let { PanelConfig.BASE_URL + ShopClient.invoicePath(it) } ?: PanelConfig.SHOP_URL
    }

    private fun showLoading(msg: String) {
        body.removeAllViews()
        footerBox.visibility = View.GONE
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
        footerBox.visibility = View.GONE
        val c = card()
        c.addView(text(msg, 15f, Ui.TEXT).apply { gravity = Gravity.CENTER })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(outlineButton(getString(R.string.zfy_open_web)).apply {
            setOnClickListener { openWeb(currentWebUrl(), step) }
            Tv.focusRing(this, 24f)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        val retryBtn = primaryButton(getString(R.string.zfy_retry)).apply {
            setOnClickListener { retry() }
            Tv.focusRing(this, 24f)
        }
        row.addView(retryBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        Tv.initialFocus(retryBtn)
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
        footerBox.visibility = View.GONE
        if (list.isEmpty()) {
            val c = card()
            c.addView(text(getString(R.string.zfy_no_plans), 15f, Ui.TEXT).apply { gravity = Gravity.CENTER })
            c.addView(outlineButton(getString(R.string.zfy_open_web)).apply {
                setOnClickListener { openWeb(PanelConfig.SHOP_URL, Step.Products) }
                Tv.focusRing(this, 24f)
                Tv.initialFocus(this)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
            body.addView(c, matchWrap().apply { topMargin = dp(24) })
            return
        }
        body.addView(text(getString(R.string.zfy_pick_plan_hint), 13f, Ui.TEXT_SUB).apply {
            setPadding(dp(4), dp(4), 0, dp(4))
        })
        var lastCat: String? = "\u0000"
        firstFocus = null
        ShopParser.groupProducts(list).forEach { g ->
            if (g.category != lastCat) {
                lastCat = g.category
                if (g.category != null) {
                    body.addView(text(g.category, 14f, Ui.TEXT_SUB, true).apply {
                        setPadding(dp(4), dp(14), 0, dp(2))
                    })
                }
            }
            body.addView(planCard(g), matchWrap().apply { topMargin = dp(10) })
        }
        // D-pad: start on the first plan (its selected duration chip, or its 购买 button).
        firstFocus?.let { Tv.initialFocus(it) }
    }

    /** First D-pad target of the plan list, set while rendering. */
    private var firstFocus: View? = null

    /** Selected period per plan card, kept while the activity lives (e.g. back from 确认订单). */
    private val selectedOption = HashMap<String, String>()

    private fun planKey(g: ShopParser.PlanGroup) = (g.category ?: "") + "\u0000" + g.name

    /**
     * One card per plan: heading, features, notes, a row of duration chips (月付 / 季付 / …, each with
     * its price; sold-out ones disabled with 已售罄) and a 购买 button for the selected chip.
     * Single-option plans show just their price.
     */
    private fun planCard(g: ShopParser.PlanGroup): View {
        val c = card()
        val key = planKey(g)
        var selected = g.options.firstOrNull { it.id == selectedOption[key] && it.available } ?: g.defaultOption()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(g.name, 17f, Ui.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val priceBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val priceView = text("", 21f, Ui.BLUE, true)
        val periodView = text("", 12f, Ui.TEXT_SUB).apply { setPadding(dp(4), 0, 0, dp(3)) }
        priceBox.addView(priceView)
        priceBox.addView(periodView)
        top.addView(priceBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        c.addView(top)

        if (g.features.isNotEmpty()) {
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
            g.features.forEach { (k, v) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                row.addView(text(k, 13f, Ui.TEXT_SUB), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(text(v, 13f, Ui.TEXT, true))
                grid.addView(row)
            }
            c.addView(grid)
        }
        if (!g.description.isNullOrBlank()) {
            c.addView(text(g.description, 12f, Ui.TEXT_SUB).apply {
                setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.15f)
            })
        }

        val buy = primaryButton(getString(R.string.zfy_buy))
        Tv.focusRing(buy, 24f)
        val chips = ArrayList<Pair<ShopParser.Product, TextView>>()

        fun refresh() {
            priceView.text = shop.money(selected.price) ?: "—"
            val per = selected.period
            periodView.text = if (per != null) "/ $per" else ""
            periodView.visibility = if (per != null) View.VISIBLE else View.GONE
            chips.forEach { (o, v) -> styleChip(v, o, o.id == selected.id) }
            buy.isEnabled = selected.available
            buy.alpha = if (selected.available) 1f else 0.5f
            buy.text = getString(if (selected.available) R.string.zfy_buy else R.string.zfy_sold_out)
        }

        if (g.options.size > 1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val weighted = g.options.size <= 5
            val longest = g.options.maxOf { (shop.money(it.price) ?: "").length.coerceAtLeast(it.period?.length?.times(2) ?: 0) }
            val chipSize = when { longest >= 9 -> 10f; longest >= 7 -> 11f; else -> 12f }
            g.options.forEachIndexed { i, o ->
                val chip = TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = chipSize
                    setLineSpacing(0f, 1.1f)
                    maxLines = 2
                    setPadding(dp(2), dp(7), dp(2), dp(7))
                    isEnabled = o.available
                    setOnClickListener {
                        if (!o.available) return@setOnClickListener
                        selected = o
                        selectedOption[key] = o.id
                        refresh()
                    }
                }
                // Focusable for the D-pad; a horizontally scrolling row keeps the focused chip in view.
                Tv.focusRing(chip, 10f, scale = 1.06f)
                val lp = if (weighted) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
                if (i > 0) lp.leftMargin = dp(5)
                row.addView(chip, lp)
                chips += o to chip
            }
            val holder: View = if (weighted) row else android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            c.addView(holder, matchWrap().apply { topMargin = dp(12) })
            if (firstFocus == null) firstFocus = chips.firstOrNull { it.first.id == selected.id }?.second
        }
        if (firstFocus == null && g.available) firstFocus = buy

        buy.setOnClickListener {
            if (!selected.available) return@setOnClickListener
            product = selected
            preview = null
            loadConfirm()
        }
        c.addView(buy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        refresh()
        if (!g.available) c.alpha = 0.6f
        return c
    }

    private fun styleChip(v: TextView, o: ShopParser.Product, on: Boolean) {
        val period = o.period ?: getString(R.string.zfy_buy)
        val second = if (o.available) shop.money(o.price) ?: "—" else getString(R.string.zfy_sold_out)
        val span = android.text.SpannableStringBuilder(period).append("\n")
        val start = span.length
        span.append(second)
        span.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, span.length, 0)
        v.text = span
        when {
            !o.available -> {
                v.setTextColor(Ui.MUTED)
                v.background = Ui.rounded(Ui.SURFACE_ALT, dp(10).toFloat())
            }
            on -> {
                v.setTextColor(Ui.BLUE)
                v.background = Ui.rounded(Ui.SELECTED_ROW, dp(10).toFloat(), Ui.BLUE, dp(2))
            }
            else -> {
                v.setTextColor(Ui.TEXT)
                v.background = Ui.rounded(Ui.SURFACE_ALT, dp(10).toFloat(), Ui.BORDER, dp(1))
            }
        }
        Tv.refreshRing(v, 10f)
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
            setHintTextColor(Ui.TEXT_HINT)
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.rounded(Ui.SURFACE_ALT, dp(10).toFloat())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            Tv.focusRing(this, 10f, scale = 1f)
        }
        val couponStatus = text("", 13f, Ui.TEXT_SUB).apply { visibility = View.GONE; setPadding(0, dp(6), 0, 0) }
        val applyBtn = outlineButton(getString(R.string.zfy_apply))
        Tv.focusRing(applyBtn, 24f)
        // 完成 / Enter on the keyboard applies the coupon.
        couponInput.setOnEditorActionListener { v, id, ev ->
            val enter = ev != null && ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_ENTER
            if (id == EditorInfo.IME_ACTION_DONE || enter) {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
                applyBtn.performClick(); true
            } else false
        }
        couponRow.addView(couponInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        couponRow.addView(applyBtn, LinearLayout.LayoutParams(dp(76), dp(44)).apply { leftMargin = dp(8) })
        couponCard.addView(couponRow, matchWrap().apply { topMargin = dp(8) })
        couponCard.addView(couponStatus)
        body.addView(couponCard, matchWrap().apply { topMargin = dp(12) })

        // Price
        val basePrice = pv?.price ?: p.price
        val priceCard = card()
        priceCard.addView(kv(getString(R.string.zfy_price), shop.money(basePrice) ?: "—"))
        val discountRow = kv(getString(R.string.zfy_discount), "").apply { visibility = View.GONE }
        priceCard.addView(discountRow)
        val totalRow = kv(getString(R.string.zfy_total), shop.money(basePrice) ?: "—", bold = true)
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
                couponStatus.setTextColor(if (r.ok) Ui.GREEN else Ui.RED)
                if (r.ok) {
                    discountRow.visibility = View.VISIBLE
                    (discountRow.getChildAt(1) as TextView).text = "-" + (shop.money(r.discount?.trim()?.trimStart('-') ?: "0") ?: "")
                    (totalRow.getChildAt(1) as TextView).text = shop.money(r.total ?: basePrice) ?: "—"
                } else {
                    discountRow.visibility = View.GONE
                    (totalRow.getChildAt(1) as TextView).text = shop.money(basePrice) ?: "—"
                }
            }
        }

        // Footer
        footer.removeAllViews()
        footerBox.visibility = View.VISIBLE
        val create = primaryButton(getString(R.string.zfy_create_order))
        Tv.focusRing(create, 24f)
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
        Tv.initialFocus(create)
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
            top.addView(text(s, 12f, if (inv.unpaid) Ui.ORANGE else Ui.GREEN, true).apply {
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = Ui.rounded(if (inv.unpaid) 0x1FF59E0B else 0x1F22B573, dp(10).toFloat())
            })
        }
        head.addView(top)
        head.addView(text(shop.money(inv.price) ?: "—", 30f, Ui.TEXT, true).apply { setPadding(0, dp(8), 0, 0) })
        head.addView(text(getString(R.string.zfy_amount_due), 12f, Ui.TEXT_SUB))
        body.addView(head, matchWrap())

        if (inv.items.isNotEmpty() || inv.info.isNotEmpty()) {
            val detail = card()
            inv.items.forEach { (k, v) -> detail.addView(kv(k, shop.money(v) ?: v)) }
            inv.info.forEach { (k, v) -> detail.addView(kv(k, v, small = true)) }
            body.addView(detail, matchWrap().apply { topMargin = dp(12) })
        }

        footer.removeAllViews()
        footerBox.visibility = View.VISIBLE

        if (!inv.unpaid) {
            val done = card()
            done.addView(text(getString(R.string.zfy_paid_done), 15f, Ui.GREEN, true).apply { gravity = Gravity.CENTER })
            body.addView(done, matchWrap().apply { topMargin = dp(12) })
            footer.addView(primaryButton(getString(R.string.zfy_done)).apply {
                setOnClickListener { setResult(RESULT_OK); finish() }
                Tv.focusRing(this, 24f)
                Tv.initialFocus(this)
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
                if (i > 0) listCard.addView(View(this).apply { setBackgroundColor(Ui.DIVIDER) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(48) })
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                }
                Tv.focusRing(row, 10f, scale = 1f)
                val dot = View(this)
                row.addView(dot, LinearLayout.LayoutParams(dp(18), dp(18)))
                val label = if (o.kind == PayKind.Balance && inv.balance != null)
                    getString(R.string.zfy_pay_balance, shop.money(inv.balance) ?: inv.balance) else o.label
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

        val pay = primaryButton(getString(R.string.zfy_go_pay, shop.money(inv.price) ?: ""))
        pay.isEnabled = inv.options.isNotEmpty()
        Tv.focusRing(pay, 24f)
        pay.setOnClickListener { option?.let { startPay(inv, it, pay) } }
        footer.addView(pay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        footer.addView(text(getString(R.string.zfy_paid_refresh), 14f, Ui.BLUE).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { loadInvoice(inv.id, quiet = true) }
            Tv.focusRing(this, 10f, scale = 1f)
        }, matchWrap())
        scroll.scrollTo(0, 0)
        Tv.initialFocus(pay)
    }

    private fun startPay(inv: ShopParser.Invoice, o: ShopParser.PayOption, btn: TextView) {
        when (o.kind) {
            PayKind.WebOnly -> {
                openWeb(PanelConfig.BASE_URL + ShopClient.invoicePath(inv.id), Step.Invoice); return
            }
            PayKind.Balance -> {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.zfy_pay_balance_confirm, shop.money(inv.price) ?: ""))
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
                imeOptions = EditorInfo.IME_ACTION_DONE
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
            val dlg = b.show()
            input.setOnEditorActionListener { _, id, ev ->
                val enter = ev != null && ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_ENTER
                if (id == EditorInfo.IME_ACTION_DONE || enter) {
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick(); true
                } else false
            }
        }

    // ------------------------------------------------------------------ helpers

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.SURFACE, dp(14).toFloat())
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
            setStroke(dp(2), if (on) Ui.BLUE else Ui.RADIO_OFF)
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
