package com.github.kr328.clash

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.zhifeiyun.AccountStore
import com.github.kr328.clash.zhifeiyun.PanelConfig
import com.github.kr328.clash.zhifeiyun.Ui
import com.github.kr328.clash.zhifeiyun.Ui.dp
import com.github.kr328.clash.zhifeiyun.Ui.text

/**
 * Fallback for purchase steps the app could not parse: shows a panel page in an in-app WebView
 * that carries the saved session cookie. Panel pages stay inside; payment-app schemes
 * (alipays://, weixin://, intent://…) and any other domain (payment gateways) go to the system.
 * Cookies set while browsing (e.g. a re-login) are copied back to the app on close.
 */
class PanelWebActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private lateinit var progress: ProgressBar
    private val panelHost = Uri.parse(PanelConfig.BASE_URL).host ?: ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySystemBars(this, Ui.SURFACE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(12), dp(4))
        }
        bar.addView(text("‹", 28f, Ui.BLUE).apply {
            setPadding(dp(10), 0, dp(14), dp(4))
            setOnClickListener { onBackPressed() }
        })
        titleView = text(intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.zfy_app_name), 16f, Ui.TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(text(getString(R.string.zfy_close), 15f, Ui.BLUE).apply {
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { finish() }
        })
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        injectCookies(this)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = PanelConfig.USER_AGENT
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank() && !title.startsWith("http")) titleView.text = title
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                route(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                route(Uri.parse(url))

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }
        }

        web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: PanelConfig.SHOP_URL)
    }

    /** true = handled outside the WebView. */
    private fun route(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            if (uri.host.equals(panelHost, true)) return false
            openExternal(this, uri.toString())
            return true
        }
        openExternal(this, uri.toString())
        return true
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    override fun finish() {
        syncCookiesBack(this)
        setResult(RESULT_OK)
        super.finish()
    }

    override fun onDestroy() {
        runCatching {
            (web.parent as? LinearLayout)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        fun intent(c: Context, url: String, title: String? = null): Intent =
            Intent(c, PanelWebActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)

        fun injectCookies(c: Context) {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            AccountStore(c).cookies.forEach { (k, v) ->
                cm.setCookie(PanelConfig.BASE_URL, "$k=$v; path=/")
            }
            cm.flush()
        }

        fun syncCookiesBack(c: Context) {
            val raw = CookieManager.getInstance().getCookie(PanelConfig.BASE_URL) ?: return
            val map = LinkedHashMap(AccountStore(c).cookies)
            raw.split(';').forEach { part ->
                val k = part.substringBefore('=').trim()
                val v = part.substringAfter('=', "").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }
            AccountStore(c).cookies = map
        }

        /**
         * Hands a payment URL to the system: http(s) to the browser, app schemes (alipays://,
         * weixin://, …) to the installed app, intent:// URIs parsed with their browser fallback.
         */
        fun openExternal(c: Context, url: String): Boolean {
            val uri = Uri.parse(url)
            try {
                if (uri.scheme.equals("intent", true)) {
                    val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        component = null
                        selector = null
                    }
                    try {
                        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return true
                    } catch (e: ActivityNotFoundException) {
                        val fb = i.getStringExtra("browser_fallback_url")
                        if (!fb.isNullOrBlank()) {
                            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fb)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            return true
                        }
                        throw e
                    }
                }
                c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                val s = uri.scheme?.lowercase() ?: ""
                val msg = when {
                    s.startsWith("alipay") -> R.string.zfy_need_alipay
                    s.startsWith("weixin") || s.startsWith("wechat") -> R.string.zfy_need_wechat
                    else -> R.string.zfy_cannot_open
                }
                Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
                return false
            }
        }
    }
}
