package com.github.kr328.clash.zhifeiyun

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal SSPanel-UIM HTTP client. Session = cookies captured from Set-Cookie.
 * Login: POST /auth/login (form-urlencoded: email, password, remember_me) as an htmx request.
 *  - success: empty body + header "HX-Redirect: /user"
 *  - 2FA:     header "HX-Redirect: /auth/mfa"
 *  - failure: JSON {"ret":0,"msg":"邮箱或者密码错误"}
 */
class PanelClient(private val cookies: MutableMap<String, String> = mutableMapOf()) {
    sealed class LoginResult {
        object Success : LoginResult()
        object NeedMfa : LoginResult()
        data class Failed(val message: String) : LoginResult()
    }

    data class Response(val code: Int, val body: String, val headers: Map<String, List<String>>, val location: String?)

    val cookieMap: Map<String, String> get() = cookies

    fun login(email: String, password: String): LoginResult {
        // Prime the session cookie (PHPSESSID) first; ignore failures.
        try { request("GET", PanelConfig.BASE_URL + "/auth/login") } catch (_: IOException) {}

        val form = listOf(
            "email" to email.trim(),
            "password" to password,
            "remember_me" to "true",
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

        val resp = request(
            "POST", PanelConfig.BASE_URL + "/auth/login", form,
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "HX-Request" to "true",
                "HX-Current-URL" to PanelConfig.BASE_URL + "/auth/login",
                "Accept" to "*/*",
                "Origin" to PanelConfig.BASE_URL,
                "Referer" to PanelConfig.BASE_URL + "/auth/login",
            )
        )

        val hxRedirect = header(resp, "HX-Redirect")
        if (hxRedirect != null) {
            return if (hxRedirect.contains("mfa")) LoginResult.NeedMfa else LoginResult.Success
        }
        val parsed = PanelParser.parseRetMsg(resp.body)
        if (parsed != null) {
            return if (parsed.first == 1 && cookies.isNotEmpty()) LoginResult.Success
            else LoginResult.Failed(parsed.second.ifBlank { "登录失败" })
        }
        if (resp.code in 200..399 && cookies.keys.any { it == "uid" || it == "key" }) return LoginResult.Success
        return LoginResult.Failed("登录失败（HTTP ${resp.code}）")
    }

    /** Fetches /user. Returns null if the session is no longer valid. */
    fun fetchUserPage(): String? {
        val resp = request("GET", PanelConfig.BASE_URL + "/user")
        if (resp.code in 300..399) return null
        if (PanelParser.isLoginPage(resp.body)) return null
        if (resp.code != 200) return null
        return resp.body
    }

    private fun header(resp: Response, name: String): String? =
        resp.headers.entries.firstOrNull { it.key != null && it.key.equals(name, true) }?.value?.firstOrNull()

    fun request(method: String, url: String, body: String? = null, extraHeaders: Map<String, String> = emptyMap()): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", PanelConfig.USER_AGENT)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (cookies.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val headers = conn.headerFields ?: emptyMap()
            headers.entries.filter { it.key != null && it.key.equals("Set-Cookie", true) }
                .flatMap { it.value }
                .forEach { raw ->
                    val pair = raw.substringBefore(';')
                    val k = pair.substringBefore('=').trim()
                    val v = pair.substringAfter('=', "").trim()
                    if (k.isEmpty()) return@forEach
                    if (v.isEmpty() || v == "deleted" || raw.contains("Max-Age=0", true)) cookies.remove(k)
                    else cookies[k] = v
                }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return Response(code, text, headers.filterKeys { it != null }, conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }
}
