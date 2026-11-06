package com.github.kr328.clash

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.zhifeiyun.AccountStore
import com.github.kr328.clash.zhifeiyun.PanelClient
import com.github.kr328.clash.zhifeiyun.PanelConfig
import com.github.kr328.clash.zhifeiyun.PanelParser
import com.github.kr328.clash.zhifeiyun.SubscriptionManager
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
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        setContentView(buildView())
        AccountStore(this).email?.let { emailInput.setText(it) }
    }

    private fun buildView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(48), dp(28), dp(28))
        }

        col.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(150), dp(150)))

        col.addView(text(getString(R.string.zfy_app_name), 28f, Ui.BLUE_DARK, true).apply {
            gravity = Gravity.CENTER
        })
        col.addView(text(getString(R.string.zfy_login_welcome), 14f, Ui.TEXT_SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(28))
        })

        emailInput = input(getString(R.string.zfy_email_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        passwordInput = input(getString(R.string.zfy_password_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { doLogin(); true } else false
            }
        }
        col.addView(emailInput, fullWidth(dp(52)))
        col.addView(passwordInput, fullWidth(dp(52)).apply { topMargin = dp(12) })

        loginButton = primaryButton(getString(R.string.zfy_login)).apply { setOnClickListener { doLogin() } }
        col.addView(loginButton, fullWidth(dp(52)).apply { topMargin = dp(24) })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        col.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(16) })

        status = text("", 13f, Ui.TEXT_SUB).apply { gravity = Gravity.CENTER }
        col.addView(status, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val links = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        links.addView(outlineButton(getString(R.string.zfy_register)).apply {
            setOnClickListener { openUrl(PanelConfig.REGISTER_URL) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        links.addView(outlineButton(getString(R.string.zfy_forgot)).apply {
            setOnClickListener { openUrl(PanelConfig.RESET_URL) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        col.addView(links, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })

        col.addView(text(getString(R.string.zfy_attribution), 11f, Ui.TEXT_SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            fitsSystemWindows = true
            addView(col)
        }
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT_SUB)
        textSize = 16f
        setPadding(dp(16), 0, dp(16), 0)
        background = Ui.rounded(Ui.GREY_BG, dp(12).toFloat())
    }

    private fun fullWidth(height: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)

    private fun setBusy(busy: Boolean, msg: String = "") {
        loginButton.isEnabled = !busy
        emailInput.isEnabled = !busy
        passwordInput.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.text = msg
    }

    private fun doLogin() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.zfy_fill_all, Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true, getString(R.string.zfy_logging_in))

        launch {
            try {
                val client = PanelClient()
                val result = withContext(Dispatchers.IO) { client.login(email, password) }
                when (result) {
                    is PanelClient.LoginResult.Failed -> {
                        setBusy(false, result.message); return@launch
                    }
                    PanelClient.LoginResult.NeedMfa -> {
                        setBusy(false, getString(R.string.zfy_mfa_unsupported)); return@launch
                    }
                    PanelClient.LoginResult.Success -> Unit
                }

                status.text = getString(R.string.zfy_fetching_sub)
                val html = withContext(Dispatchers.IO) { client.fetchUserPage() }
                if (html == null) {
                    setBusy(false, getString(R.string.zfy_session_failed)); return@launch
                }
                val subUrl = PanelParser.parseClashSubUrl(html)
                if (subUrl == null) {
                    setBusy(false, getString(R.string.zfy_sub_not_found)); return@launch
                }

                val store = AccountStore(this@LoginActivity)
                store.email = email
                store.cookies = client.cookieMap
                store.htmlRemaining = PanelParser.parseRemainingTraffic(html)
                store.htmlExpire = PanelParser.parseExpire(html)
                store.autoNodeApplied = false

                status.text = getString(R.string.zfy_importing)
                SubscriptionManager.importSubscription(this@LoginActivity, subUrl)
                store.subUrl = subUrl

                startActivity(Intent(this@LoginActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (e: Exception) {
                setBusy(false, getString(R.string.zfy_network_error) + "\n" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }
}
