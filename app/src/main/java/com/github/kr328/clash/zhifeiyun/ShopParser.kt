package com.github.kr328.clash.zhifeiyun

import org.json.JSONObject

/**
 * All parsing for the in-app purchase flow (商品 → 订单 → 账单 → 支付) lives in this file.
 * Every function returns null / empty when unsure; the caller then falls back to an
 * in-app WebView for that step.
 *
 * Based on SSPanel-UIM (tabler theme, htmx) as of 2026-11:
 *  - GET  /user/product                       product cards: id="product-{id}-name", id="product-{id}-price",
 *                                             feature rows (text-reset d-block value + text-secondary label),
 *                                             buy link /user/order/create?product_id={id} (absent when sold out),
 *                                             tab panes #tabp (时间流量包) / #bandwidth (流量包) / #time (时间包)
 *  - GET  /user/order/create?product_id={id}  <td>label</td><td>value</td> rows (商品时长, 可用流量, 商品价格 …)
 *  - POST /user/coupon                        coupon, product_id -> {"ret":1,"data":{"product-buy-discount","product-buy-total"}}
 *  - POST /user/order/create                  type=product, coupon, product_id -> header HX-Redirect: /user/invoice/{id}/view
 *                                             or {"ret":0,"msg":"…"}
 *  - GET  /user/invoice/{id}/view             datagrid (订单金额, 订单状态 …), #invoice_content_table rows,
 *                                             "当前账户可用余额：<code>X</code>", gateway buttons
 *                                             hx-post="/user/payment/purchase/{gateway}" hx-vals='js:{ … }'
 *  - POST /user/invoice/pay_balance           invoice_id -> HX-Redirect /user/invoice (paid) | HX-Refresh (partial)
 *  - POST /user/payment/purchase/{gateway}    invoice_id (+ type, redir, price) -> HX-Redirect: <pay url>
 *                                             or {"ret":1,"qrcode":"…"} (f2f / smogate) or {"ret":0,"msg":"…"}
 */
object ShopParser {
    data class Product(
        val id: String,
        val name: String,
        val price: String?,
        val category: String?,
        val features: List<Pair<String, String>>,
        val description: String?,
        val available: Boolean,
    )

    data class OrderPreview(
        val details: List<Pair<String, String>>,
        val price: String?,
    )

    data class CouponResult(val ok: Boolean, val message: String, val discount: String?, val total: String?)

    enum class PayKind { Redirect, Qr, WebOnly, Balance }

    data class PayOption(
        val key: String,
        val label: String,
        val gateway: String,
        val params: Map<String, String>,
        val kind: PayKind,
    )

    data class Invoice(
        val id: String,
        val price: String?,
        val status: String?,
        val unpaid: Boolean,
        val info: List<Pair<String, String>>,
        val items: List<Pair<String, String>>,
        val balance: String?,
        val options: List<PayOption>,
    )

    /** Result of an htmx POST: redirect target, a QR payload, or a ret/msg message. */
    sealed class ActionResult {
        data class Redirect(val url: String) : ActionResult()
        object Refresh : ActionResult()
        data class Qr(val content: String) : ActionResult()
        data class Message(val ok: Boolean, val message: String) : ActionResult()
        object Unknown : ActionResult()
    }

    // ---------------------------------------------------------------- products

    private val nameMarker = Regex("""id=["']product-(\d+)-name["'][^>]*>""")
    private val buyLink = Regex("""/user/order/create\?product_id=(\d+)""")
    private val featureRe = Regex(
        """class=["'][^"']*text-reset[^"']*["'][^>]*>(.*?)</div>\s*<div[^>]*class=["'][^"']*text-secondary[^"']*["'][^>]*>(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val numberRe = Regex("""(\d+(?:\.\d+)?)""")

    fun parseProducts(html: String): List<Product>? {
        val tabs = parseTabNames(html)
        val markers = nameMarker.findAll(html).toList()
        if (markers.isNotEmpty()) {
            return markers.mapIndexed { i, m ->
                val id = m.groupValues[1]
                val start = m.range.first
                val end = if (i + 1 < markers.size) markers[i + 1].range.first else html.length
                val seg = html.substring(start, end)
                val name = clean(seg.substring(m.value.length).substringBefore("</div>"))
                val priceHtml = Regex("""id=["']product-$id-price["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                    .find(seg)?.groupValues?.get(1)
                val price = priceHtml?.let { numberRe.find(clean(it))?.value }
                val features = featureRe.findAll(seg)
                    .map { clean(it.groupValues[2]) to clean(it.groupValues[1]) }
                    .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                    .toList()
                val desc = Regex("""id=["']product-$id-(?:desc|description|content)["'][^>]*>(.*?)</(?:div|p)>""", RegexOption.DOT_MATCHES_ALL)
                    .find(seg)?.groupValues?.get(1)?.let { clean(it) }?.ifBlank { null }
                Product(
                    id = id,
                    name = name.ifBlank { "商品 #$id" },
                    price = price,
                    category = categoryAt(html, start, tabs),
                    features = features,
                    description = desc,
                    available = seg.contains("product_id=$id"),
                )
            }
        }
        // Customised theme without the id markers: take the card around each buy link.
        val links = buyLink.findAll(html).toList().distinctBy { it.groupValues[1] }
        if (links.isEmpty()) {
            return if (html.contains("/user/product") || html.contains("商品")) emptyList() else null
        }
        val out = links.mapNotNull { m ->
            val id = m.groupValues[1]
            val cardStart = html.lastIndexOf("class=\"card", m.range.first).takeIf { it >= 0 }
                ?: (m.range.first - 2000).coerceAtLeast(0)
            val lines = textLines(html.substring(cardStart, m.range.first))
                .filter { it != "购买" && it != "立即购买" }
            if (lines.isEmpty()) return@mapNotNull null
            val priceLine = lines.firstOrNull { Regex("""^[¥￥]?\s*\d+(\.\d+)?\s*(元|¥|￥)?(\s*/.*)?$""").matches(it) }
            val rest = lines.drop(1).filter { it != priceLine }.take(6)
            Product(
                id = id,
                name = lines.first(),
                price = priceLine?.let { numberRe.find(it)?.value },
                category = categoryAt(html, m.range.first, tabs),
                features = emptyList(),
                description = rest.joinToString(" · ").ifBlank { null },
                available = true,
            )
        }
        return out.ifEmpty { null }
    }

    private fun parseTabNames(html: String): Map<String, String> {
        val map = HashMap<String, String>()
        Regex("""href=["']#([\w-]+)["'][^>]*data-bs-toggle=["']tab["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { map[it.groupValues[1]] = clean(it.groupValues[2]) }
        return map
    }

    private fun categoryAt(html: String, pos: Int, tabs: Map<String, String>): String? {
        val pane = Regex("""class=["'][^"']*tab-pane[^"']*["'][^>]*id=["']([\w-]+)["']""")
            .findAll(html.substring(0, pos)).lastOrNull()?.groupValues?.get(1) ?: return null
        return tabs[pane]?.ifBlank { null } ?: when (pane) {
            "tabp" -> "时间流量包"
            "bandwidth" -> "流量包"
            "time" -> "时间包"
            else -> null
        }
    }

    // ---------------------------------------------------------------- order preview

    private val tdRow = Regex("""<tr[^>]*>\s*<td[^>]*>(.*?)</td>\s*<td[^>]*>(.*?)</td>\s*</tr>""", RegexOption.DOT_MATCHES_ALL)

    fun parseOrderPreview(html: String): OrderPreview? {
        val rows = tdRow.findAll(html).map { clean(it.groupValues[1]) to clean(it.groupValues[2]) }.toList()
        if (rows.isEmpty()) return null
        val price = rows.firstOrNull { it.first == "商品价格" }?.second
        val stop = setOf("商品价格", "优惠码", "优惠金额", "实际支付")
        val details = rows.filter { it.first != "商品ID" && it.first !in stop && it.second.isNotEmpty() }
        return OrderPreview(details, price)
    }

    // ---------------------------------------------------------------- coupon

    fun parseCoupon(body: String): CouponResult? = runCatching {
        val o = JSONObject(body)
        val ok = o.optInt("ret", 0) == 1
        val data = o.optJSONObject("data")
        CouponResult(
            ok = ok,
            message = o.optString("msg", if (ok) "优惠码可用" else "优惠码无效"),
            discount = data?.opt("product-buy-discount")?.toString(),
            total = data?.opt("product-buy-total")?.toString(),
        )
    }.getOrNull()

    // ---------------------------------------------------------------- htmx action responses

    fun parseAction(hxRedirect: String?, hxRefresh: String?, body: String): ActionResult {
        if (!hxRedirect.isNullOrBlank()) return ActionResult.Redirect(hxRedirect.trim())
        if (hxRefresh.equals("true", true)) return ActionResult.Refresh
        val o = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return ActionResult.Unknown
        val ret = o.optInt("ret", -1)
        val qr = o.optString("qrcode", "")
        if (ret == 1 && qr.isNotBlank()) return ActionResult.Qr(qr)
        val url = o.optString("url", "").ifBlank { o.optString("payurl", "") }
        if (url.startsWith("http")) return ActionResult.Redirect(url)
        if (ret == 0 || ret == 1) return ActionResult.Message(ret == 1, o.optString("msg", ""))
        return ActionResult.Unknown
    }

    fun invoiceIdFromRedirect(url: String): String? =
        Regex("""/user/invoice/(\d+)/view""").find(url)?.groupValues?.get(1)

    // ---------------------------------------------------------------- invoice

    private val datagrid = Regex(
        """datagrid-title["'][^>]*>(.*?)</div>\s*<div[^>]*datagrid-content["'][^>]*>(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parseInvoice(id: String, html: String): Invoice? {
        val info = datagrid.findAll(html).map { clean(it.groupValues[1]) to clean(it.groupValues[2]) }.toList()
        val tableHtml = html.substringAfter("invoice_content_table", "").substringBefore("</table>")
        val items = Regex("""<tr[^>]*>\s*<td[^>]*>(.*?)</td>\s*<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(tableHtml).map { clean(it.groupValues[1]) to clean(it.groupValues[2]) }.toList()
        if (info.isEmpty() && items.isEmpty()) return null

        val price = info.firstOrNull { it.first.contains("金额") }?.second
        val status = info.firstOrNull { it.first.contains("状态") }?.second
        val options = parsePayOptions(id, html)
        val unpaid = options.isNotEmpty() || html.contains("/user/invoice/pay_balance") ||
            (status != null && (status.contains("未支付") || status.contains("部分")))
        val balance = Regex("""可用余额\s*[:：]?\s*(?:<code>)?\s*([0-9.\-]+)""").find(html)?.groupValues?.get(1)
        return Invoice(
            id = id,
            price = price,
            status = status,
            unpaid = unpaid,
            info = info.filter { !it.first.contains("金额") && !it.first.contains("状态") && it.second.isNotEmpty() },
            items = items,
            balance = balance,
            options = options,
        )
    }

    private fun parsePayOptions(invoiceId: String, html: String): List<PayOption> {
        val out = ArrayList<PayOption>()
        if (html.contains("/user/invoice/pay_balance")) {
            out += PayOption("balance", "余额支付", "balance", mapOf("invoice_id" to invoiceId), PayKind.Balance)
        }
        // htmx buttons: <button … hx-post="/user/payment/purchase/{gw}" … hx-vals='js:{ … }'> label </button>
        Regex("""hx-post=["']/user/payment/purchase/([\w-]+)["']""").findAll(html).forEach { m ->
            val gw = m.groupValues[1]
            val btnStart = html.lastIndexOf("<button", m.range.first).let { if (it < 0) m.range.first else it }
            val btnEnd = html.indexOf("</button>", m.range.last).let { if (it < 0) html.length else it }
            val tag = html.substring(btnStart, btnEnd)
            val vals = Regex("""hx-vals=(['"])(.*?)\1""", RegexOption.DOT_MATCHES_ALL).find(tag)?.groupValues?.get(2) ?: ""
            val params = LinkedHashMap<String, String>()
            Regex("""(\w+)\s*:\s*(?:"([^"]*)"|'([^']*)'|(-?[0-9.]+))""").findAll(vals).forEach { p ->
                val v = p.groupValues[2].ifEmpty { p.groupValues[3] }.ifEmpty { p.groupValues[4] }
                params[p.groupValues[1]] = v
            }
            params["invoice_id"] = invoiceId
            val inner = tag.substringAfterLast("'>", tag.substringAfter(">"))
            val img = Regex("""<img[^>]*src=["']([^"']+)["']""").find(inner)?.groupValues?.get(1)
            val label = gatewayLabel(gw, params["type"], img, clean(inner))
            val key = gw + ":" + (params["type"] ?: "")
            if (out.none { it.key == key }) out += PayOption(key, label, gw, params, PayKind.Redirect)
        }
        // jQuery gateways returning a QR payload (支付宝当面付 / smogate).
        Regex("""url:\s*["']/user/payment/purchase/(f2f|smogate)["']""").findAll(html).forEach { m ->
            val gw = m.groupValues[1]
            val params = linkedMapOf("invoice_id" to invoiceId)
            if (gw == "smogate") {
                Regex("""id=["']amount-smogate["'][^>]*value=["']([^"']*)["']""").find(html)
                    ?.let { params["amount"] = it.groupValues[1] }
            }
            if (out.none { it.gateway == gw }) out += PayOption(gw, gatewayLabel(gw, null, null, ""), gw, params, PayKind.Qr)
        }
        // PayPal JS SDK: only works inside the web page.
        if (html.contains("/user/payment/purchase/paypal") && out.none { it.gateway == "paypal" }) {
            out += PayOption("paypal", "PayPal", "paypal", mapOf("invoice_id" to invoiceId), PayKind.WebOnly)
        }
        return out
    }

    private fun gatewayLabel(gw: String, type: String?, img: String?, text: String): String {
        val byType = when (type?.lowercase()) {
            "alipay" -> "支付宝"
            "wxpay", "wechat" -> "微信支付"
            "qqpay" -> "QQ 钱包"
            "usdt", "epusdt" -> "USDT"
            else -> null
        }
        if (byType != null) return byType
        val byImg = img?.lowercase()?.let {
            when {
                "alipay" in it -> "支付宝"
                "wechat" in it || "wxpay" in it -> "微信支付"
                "qq" in it -> "QQ 钱包"
                "tether" in it || "usdt" in it -> "USDT"
                else -> null
            }
        }
        if (byImg != null) return byImg
        return when (gw.lowercase()) {
            "f2f" -> "支付宝当面付"
            "smogate" -> "支付宝"
            "stripe" -> "银行卡（Stripe）"
            "cryptomus" -> "加密货币（Cryptomus）"
            "paypal" -> "PayPal"
            "epay" -> text.ifBlank { "在线支付" }
            else -> text.ifBlank { gw }
        }
    }

    // ---------------------------------------------------------------- session

    /** True when a response means the panel session has expired. */
    fun isLoggedOut(code: Int, location: String?, body: String): Boolean =
        (code in 300..399 && (location ?: "").contains("/auth/login")) ||
            PanelParser.isLoginPage(body)

    // ---------------------------------------------------------------- helpers

    private fun clean(html: String): String =
        html.replace(Regex("(?s)<script.*?</script>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun textLines(html: String): List<String> =
        html.replace(Regex("(?s)<script.*?</script>"), "\n")
            .replace(Regex("<[^>]+>"), "\n")
            .replace("&nbsp;", " ")
            .split('\n')
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("class=") }
}
