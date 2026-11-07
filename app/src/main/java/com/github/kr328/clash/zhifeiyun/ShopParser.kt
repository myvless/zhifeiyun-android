package com.github.kr328.clash.zhifeiyun

import org.json.JSONObject

/**
 * All parsing for the in-app purchase flow (商品 → 订单 → 账单 → 支付) lives in this file.
 * Every function returns null / empty when unsure; the caller then falls back to an
 * in-app WebView for that step.
 *
 * Based on SSPanel-UIM (tabler theme, htmx) as of 2026-11:
 *  - panel.020178.xyz (customised, observed 2026-11-07): /user/product has 3 inline tab panes
 *    (时间流量包 / 流量包 / 时间包); a 时间流量包 card (e.g. "vip1") holds several buttons
 *    "月付 1.00", "季付 2.50" … each <a href="/user/order/create?product_id=N">; 流量包 cards use the
 *    stock layout with a "购买" button. Currency is USD: "$" icon, "价格明细（美元）", "10 美元".
 *    Invoice view adds a 货币 column and a #balance-pay-btn button.
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

    private val nameMarker = Regex("""id\s*=\s*["']product-(\d+)-name["'][^>]*>""")
    private val buyLink = Regex("""href\s*=\s*["']([^"']*/user/order/create\?[^"']*?product_id=(\d+)[^"']*)["']""")
    private val featureRe = Regex(
        """class=["'][^"']*text-reset[^"']*["'][^>]*>(.*?)</div>\s*<div[^>]*class=["'][^"']*text-secondary[^"']*["'][^>]*>(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val numberRe = Regex("""(-?\d+(?:\.\d+)?)""")
    private val currencyGlyphs = Regex("""[¥￥$€]|美元|人民币|元|USD|CNY|RMB""")
    private val priceLineRe = Regex("""^(?:[¥￥$€]|US\$)?\s*\d+(?:\.\d+)?\s*(?:元|美元|[¥￥$€]|USD|CNY)?(?:\s*/.*)?$""")
    private val buyWords = setOf("购买", "立即购买", "订阅", "立即订阅", "选择", "下单", "告罄", "售罄", "已售罄", "buy", "purchase")
    private fun isPriceLine(s: String): Boolean =
        priceLineRe.matches(s) && (s.contains('.') || currencyGlyphs.containsMatchIn(s))

    private val glyphOnly = Regex("""^(?:[¥￥$€]|美元|元|USD|CNY)$""")

    private data class Pane(val id: String?, val start: Int, val end: Int, val title: String?)
    private data class Found(val pos: Int, val product: Product)

    /**
     * Reads every product on /user/product, across all tab panes / categories.
     *
     * Customised panels group several products into one card (e.g. a "vip1" card with buttons
     * "月付 1.00", "季付 2.50" … each linking to a different product_id), while others use the
     * stock one-product-per-card template. So the primary source is every
     * `/user/order/create?product_id=N` link on the page (de-duplicated by id); for each link we
     * take the enclosing card for name/features, the button label for price + duration, and the
     * enclosing tab pane (mapped to its tab title) for the category. Stock `product-N-name`
     * markers without a buy link are added as sold out.
     */
    fun parseProducts(html: String): List<Product>? {
        val doc = stripScripts(html)
        val panes = parsePanes(doc)
        val found = ArrayList<Found>()
        val seen = HashSet<String>()
        val links = buyLink.findAll(doc).toList()
        val linkStarts = links.map { tagStart(doc, it.range.first) }

        for ((i, m) in links.withIndex()) {
            val id = m.groupValues[2]
            if (!seen.add(id)) continue
            val aStart = linkStarts[i]
            val aOpenEnd = doc.indexOf('>', m.range.last).let { if (it < 0) m.range.last else it + 1 }
            val aEnd = doc.indexOf("</a>", aOpenEnd).let { if (it < 0) (aOpenEnd + 200).coerceAtMost(doc.length) else it }
            val label = clean(doc.substring(aOpenEnd, aEnd))
            val pane = panes.lastOrNull { aStart >= it.start && aStart < it.end }
            val floor = pane?.start ?: 0
            val cardStart = cardStartBefore(doc, aStart, floor).takeIf { it >= 0 }
                ?: noCardStart(doc, links, linkStarts, i, floor)
            // The card's header region: from the card start to its first buy button.
            val firstBtn = linkStarts.firstOrNull { it >= cardStart } ?: aStart
            val header = doc.substring(cardStart, firstBtn.coerceAtLeast(cardStart))
            val card = describeCard(header, id)

            // "1天 0.00" / "月付 1.00": the price is the last (preferably decimal) number; the rest is the duration.
            val labelPlain = label.replace(",", "")
            val nums = numberRe.findAll(labelPlain).toList()
            val priceMatch = nums.lastOrNull { it.value.contains('.') } ?: nums.lastOrNull()
            val labelNum = priceMatch?.value
            val variant = (if (priceMatch != null) labelPlain.removeRange(priceMatch.range) else labelPlain)
                .replace(currencyGlyphs, " ").replace(Regex("\\s+"), " ").trim()
            val isVariant = labelNum != null && variant.isNotEmpty() && variant.lowercase() !in buyWords
            val markerName = markerName(doc, id)
            val base = markerName ?: card.name
            val name = when {
                isVariant && base != null && !base.contains(variant) -> "$base $variant"
                isVariant && base == null -> variant
                else -> base
            } ?: label.takeIf { it.isNotEmpty() && it.lowercase() !in buyWords } ?: "商品 #$id"
            val price = (if (labelNum != null) labelNum else null)
                ?: markerPrice(doc, id)
                ?: card.price
            val soldOut = label.contains("告罄") || label.contains("售罄")
            found += Found(aStart, Product(
                id = id,
                name = name,
                price = price,
                category = pane?.title,
                features = card.features,
                description = card.description,
                available = !soldOut,
            ))
        }

        // Stock template: a sold-out card keeps its product-N-name marker but has no buy link.
        nameMarker.findAll(doc).forEach { m ->
            val id = m.groupValues[1]
            if (!seen.add(id)) return@forEach
            val pane = panes.lastOrNull { m.range.first >= it.start && m.range.first < it.end }
            val cardStart = cardStartBefore(doc, m.range.first, pane?.start ?: 0).takeIf { it >= 0 }
                ?: (pane?.start ?: 0).coerceAtLeast(m.range.first - 200)
            val nextCard = Regex("""id\s*=\s*["']product-\d+-name["']""").find(doc, m.range.last)?.range?.first ?: doc.length
            val card = describeCard(doc.substring(cardStart, nextCard.coerceAtMost((cardStart + 6000).coerceAtMost(doc.length))), id)
            found += Found(m.range.first, Product(
                id = id,
                name = markerName(doc, id) ?: card.name ?: "商品 #$id",
                price = markerPrice(doc, id) ?: card.price,
                category = pane?.title,
                features = card.features,
                description = card.description,
                available = false,
            ))
        }

        if (found.isEmpty()) {
            return if (doc.contains("/user/product") || doc.contains("商品")) emptyList() else null
        }
        return found.sortedBy { it.pos }.map { it.product }
    }

    private data class CardInfo(
        val name: String?,
        val price: String?,
        val features: List<Pair<String, String>>,
        val description: String?,
    )

    private fun describeCard(header: String, id: String): CardInfo {
        val lines = textLines(header).filter { !glyphOnly.matches(it) && it.lowercase() !in buyWords }
        val heading = Regex(
            """<(h[1-6]|div|span|p|strong)\b[^>]*class=["'][^"']*(?:card-title|text-uppercase|product-name|plan-name)[^"']*["'][^>]*>(.*?)</\1>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(header)?.groupValues?.get(2)?.let { clean(it) }?.ifBlank { null }
            ?: Regex("""<(h[1-6])\b[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
                .find(header)?.groupValues?.get(2)?.let { clean(it) }?.ifBlank { null }
        val name = heading ?: lines.firstOrNull { !isPriceLine(it) && !looksLikeValue(it) } ?: lines.firstOrNull { !isPriceLine(it) }
        val price = lines.firstOrNull { isPriceLine(it) }?.let { numberRe.find(it)?.value }

        var features = featureRe.findAll(header)
            .map { clean(it.groupValues[2]) to clean(it.groupValues[1]) }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .toList()
        val used = HashSet<String>()
        features.forEach { used += it.first; used += it.second }
        name?.let { used += it }
        val rest = lines.filter { it !in used && !isPriceLine(it) }.toMutableList()
        val desc = ArrayList<String>()
        if (features.isEmpty()) {
            // Text-only fallback: "100GB" followed by "可用流量（每30天）" -> 可用流量（每30天） 100GB
            val paired = ArrayList<Pair<String, String>>()
            var i = 0
            while (i < rest.size) {
                val v = rest[i]
                val k = rest.getOrNull(i + 1)
                if (k != null && looksLikeValue(v) && !looksLikeValue(k) && k.length <= 16) {
                    paired += k to v; i += 2
                } else {
                    desc += v; i += 1
                }
            }
            features = paired
        } else {
            desc += rest
        }
        return CardInfo(
            name = name,
            price = price,
            features = features,
            description = desc.distinct().take(4).joinToString(" · ").ifBlank { null },
        )
    }

    private fun looksLikeValue(s: String): Boolean =
        s.isNotEmpty() && (s[0].isDigit() || s.startsWith("Lv", true) || s == "不限制" || s == "无限制" || s == "无限")

    private fun markerName(doc: String, id: String): String? =
        Regex("""id\s*=\s*["']product-$id-name["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(doc)?.groupValues?.get(1)?.let { clean(it) }?.ifBlank { null }

    private fun markerPrice(doc: String, id: String): String? =
        Regex("""id\s*=\s*["']product-$id-price["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            .find(doc)?.groupValues?.get(1)?.let { numberRe.find(clean(it).replace(",", ""))?.value }

    private fun tagStart(doc: String, attrPos: Int): Int =
        doc.lastIndexOf('<', attrPos).let { if (it < 0) attrPos else it }

    /** Start of the innermost element whose class list contains the token "card" (not card-body). */
    private fun cardStartBefore(doc: String, pos: Int, floor: Int): Int {
        val from = floor.coerceAtLeast(pos - 12000).coerceAtLeast(0)
        val re = Regex("""<[a-zA-Z]+\b[^>]*\bclass\s*=\s*["'](?:[^"']*\s)?card(?:\s[^"']*)?["']""")
        val last = re.findAll(doc.substring(from, pos)).lastOrNull()
        return if (last != null) from + last.range.first else -1
    }

    /**
     * No element with a "card" class: walk back over sibling buy buttons (nothing but tags between
     * them) and start right after the previous card's last button, or ~2500 chars back.
     */
    private fun noCardStart(doc: String, links: List<MatchResult>, starts: List<Int>, index: Int, floor: Int): Int {
        var j = index - 1
        var cur = starts[index]
        while (j >= 0) {
            val prevEnd = doc.indexOf("</a>", links[j].range.last).let { if (it < 0) starts[j] else it + 4 }
            if (prevEnd < floor || prevEnd > cur) break
            if (textLines(doc.substring(prevEnd, cur)).isNotEmpty()) return prevEnd
            cur = starts[j]
            j--
        }
        return floor.coerceAtLeast(cur - 2500).coerceAtLeast(0)
    }

    /** Tab panes with their tab titles, in document order. */
    private fun parsePanes(doc: String): List<Pane> {
        val strict = LinkedHashMap<String, String>()
        val loose = LinkedHashMap<String, String>()
        Regex("""<(a|button)\b([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(doc).forEach { m ->
            val attrs = m.groupValues[2]
            val isStrict = Regex("""data-bs-toggle\s*=\s*["'](?:tab|pill)["']|data-toggle\s*=\s*["'](?:tab|pill)["']|role\s*=\s*["']tab["']""")
                .containsMatchIn(attrs)
            val isLoose = Regex("""class\s*=\s*["'][^"']*\bnav-link\b""").containsMatchIn(attrs)
            if (!isStrict && !isLoose) return@forEach
            val target = Regex("""(?:href|data-bs-target|data-target|aria-controls)\s*=\s*["']#?([\w-]+)["']""")
                .findAll(attrs).map { it.groupValues[1] }.firstOrNull { it.isNotBlank() } ?: return@forEach
            val title = clean(m.groupValues[3])
            if (title.isEmpty()) return@forEach
            val map = if (isStrict) strict else loose
            if (target !in map) map[target] = title
        }
        val titles = if (strict.isNotEmpty()) strict else loose
        val starts = ArrayList<Pair<String?, Int>>()
        Regex("""<[a-zA-Z]+\b([^>]*)>""").findAll(doc).forEach { m ->
            val attrs = m.groupValues[1]
            if (!Regex("""class\s*=\s*["'](?:[^"']*\s)?tab-pane(?:\s[^"']*)?["']""").containsMatchIn(attrs)) return@forEach
            val id = Regex("""\bid\s*=\s*["']([\w-]+)["']""").find(attrs)?.groupValues?.get(1)
            starts += id to m.range.first
        }
        if (starts.isEmpty()) return emptyList()
        val titleList = titles.values.toList()
        return starts.mapIndexed { i, (id, start) ->
            val end = starts.getOrNull(i + 1)?.second ?: doc.length
            val title = id?.let { titles[it] }
                ?: titleList.getOrNull(i)?.takeIf { titleList.size == starts.size }
                ?: when (id) {
                    "tabp" -> "时间流量包"
                    "bandwidth" -> "流量包"
                    "time" -> "时间包"
                    else -> null
                }
            Pane(id, start, end, title)
        }
    }

    // ---------------------------------------------------------------- currency

    /**
     * Currency symbol the panel uses, read from a page (product list, order, invoice, money).
     * Dollar signals win (this panel shows "美元" and a ti-currency-dollar icon); null when the page
     * says nothing. Callers default to "$", never to ¥. A yuan *icon* alone is ignored (stock
     * templates ship ti-currency-yuan even on USD panels); only explicit text such as 人民币 / CNY /
     * "（元）" / "¥1.00" counts as yuan.
     */
    fun detectCurrency(html: String): String? {
        val doc = stripScripts(html)
        if (Regex("""ti-currency-dollar|美元|\bUSD\b|US\$|\$\s?\d|>\s*\$\s*<""").containsMatchIn(doc)) return "$"
        if (Regex("""ti-currency-euro|欧元|\bEUR\b|€""").containsMatchIn(doc)) return "€"
        if (Regex("""人民币|\bCNY\b|\bRMB\b|[¥￥]\s?\d|[（(]元[)）]|</code>\s*元""").containsMatchIn(doc)) return "¥"
        return null
    }

    /** "0.30" -> "$0.30"; strips any symbol already present; "-1.00" -> "-$1.00". */
    fun formatMoney(amount: String?, symbol: String): String? {
        val raw = amount?.replace(currencyGlyphs, "")?.trim()?.ifEmpty { null } ?: return null
        return if (raw.startsWith("-")) "-" + symbol + raw.substring(1).trim() else symbol + raw
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
        val unpaid = options.isNotEmpty() || html.contains("/user/invoice/pay_balance") || html.contains("balance-pay-btn") ||
            (status != null && (status.contains("未支付") || status.contains("部分")))
        val balance = Regex("""可用余额\s*[:：]?\s*(?:<[^>]+>\s*)*([0-9.\-]+)""").find(html)?.groupValues?.get(1)
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
        if (html.contains("/user/invoice/pay_balance") || html.contains("balance-pay-btn")) {
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

    private fun stripScripts(html: String): String =
        html.replace(Regex("(?is)<script\\b.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b.*?</style>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")

    private fun textLines(html: String): List<String> =
        html.replace(Regex("(?s)<script.*?</script>"), "\u0001")
            .replace(Regex("<[^>]+>"), "\u0001")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .split('\u0001')
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("class=") }
}
