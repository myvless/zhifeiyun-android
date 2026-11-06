package com.github.kr328.clash.zhifeiyun

import android.content.Context
import java.net.URLEncoder

/**
 * HTTP side of the in-app purchase flow, using the saved panel session cookie.
 * All HTML/JSON interpretation is delegated to [ShopParser]. Blocking: call on Dispatchers.IO.
 */
class ShopClient(context: Context) {
    class SessionExpired : Exception("登录已过期")

    /** A step whose response could not be understood; open [webUrl] in the in-app WebView instead. */
    class ParseFailed(val webUrl: String) : Exception("无法解析页面")

    private val store = AccountStore(context)
    private var client = PanelClient(store.cookies.toMutableMap())

    fun reloadCookies() {
        client = PanelClient(store.cookies.toMutableMap())
    }

    private fun save() {
        store.cookies = client.cookieMap
    }

    private fun get(path: String): String {
        val resp = client.request("GET", PanelConfig.BASE_URL + path)
        save()
        if (ShopParser.isLoggedOut(resp.code, resp.location, resp.body)) throw SessionExpired()
        if (resp.code !in 200..299) throw ParseFailed(PanelConfig.BASE_URL + path)
        return resp.body
    }

    private fun post(path: String, params: Map<String, String>, referer: String): PanelClient.Response {
        val body = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val resp = client.request(
            "POST", PanelConfig.BASE_URL + path, body,
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "HX-Request" to "true",
                "HX-Current-URL" to referer,
                "Accept" to "*/*",
                "Origin" to PanelConfig.BASE_URL,
                "Referer" to referer,
            )
        )
        save()
        if (ShopParser.isLoggedOut(resp.code, resp.location, resp.body)) throw SessionExpired()
        return resp
    }

    private fun header(resp: PanelClient.Response, name: String): String? =
        resp.headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()

    private fun action(resp: PanelClient.Response): ShopParser.ActionResult =
        ShopParser.parseAction(header(resp, "HX-Redirect"), header(resp, "HX-Refresh"), resp.body)

    fun products(): List<ShopParser.Product> {
        val html = get(PanelConfig.PRODUCT_PATH)
        return ShopParser.parseProducts(html) ?: throw ParseFailed(PanelConfig.BASE_URL + PanelConfig.PRODUCT_PATH)
    }

    fun orderPreview(productId: String): ShopParser.OrderPreview? = runCatching {
        ShopParser.parseOrderPreview(get(orderCreatePath(productId)))
    }.getOrElse { if (it is SessionExpired) throw it else null }

    fun checkCoupon(productId: String, coupon: String): ShopParser.CouponResult {
        val resp = post("/user/coupon", mapOf("coupon" to coupon, "product_id" to productId),
            PanelConfig.BASE_URL + orderCreatePath(productId))
        return ShopParser.parseCoupon(resp.body)
            ?: ShopParser.CouponResult(false, "无法验证优惠码", null, null)
    }

    /** Creates the order; returns the invoice id. */
    fun createOrder(productId: String, coupon: String): String {
        val referer = PanelConfig.BASE_URL + orderCreatePath(productId)
        val resp = post("/user/order/create",
            mapOf("type" to "product", "coupon" to coupon, "product_id" to productId), referer)
        return when (val r = action(resp)) {
            is ShopParser.ActionResult.Redirect ->
                ShopParser.invoiceIdFromRedirect(r.url) ?: throw ParseFailed(absolute(r.url))
            is ShopParser.ActionResult.Message -> throw IllegalStateException(r.message.ifBlank { "创建订单失败" })
            else -> throw ParseFailed(referer)
        }
    }

    fun invoice(id: String): ShopParser.Invoice {
        val path = invoicePath(id)
        return ShopParser.parseInvoice(id, get(path)) ?: throw ParseFailed(PanelConfig.BASE_URL + path)
    }

    fun pay(invoiceId: String, option: ShopParser.PayOption, price: String?): ShopParser.ActionResult {
        val referer = PanelConfig.BASE_URL + invoicePath(invoiceId)
        if (option.kind == ShopParser.PayKind.Balance) {
            return action(post("/user/invoice/pay_balance", mapOf("invoice_id" to invoiceId), referer))
        }
        val params = LinkedHashMap(option.params)
        params["invoice_id"] = invoiceId
        params.putIfAbsent("redir", referer)
        if (price != null && option.gateway == "cryptomus") params.putIfAbsent("price", price)
        return action(post("/user/payment/purchase/" + option.gateway, params, referer))
    }

    companion object {
        fun orderCreatePath(productId: String) = "/user/order/create?product_id=$productId"
        fun invoicePath(id: String) = "/user/invoice/$id/view"
        fun absolute(url: String) = if (url.startsWith("http")) url else PanelConfig.BASE_URL + url
    }
}
