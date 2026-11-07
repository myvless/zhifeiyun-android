package com.github.kr328.clash

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.zhifeiyun.AccountStore
import com.github.kr328.clash.zhifeiyun.PanelClient
import com.github.kr328.clash.zhifeiyun.PanelConfig
import com.github.kr328.clash.zhifeiyun.PanelParser
import com.github.kr328.clash.zhifeiyun.SubscriptionManager
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
import kotlin.coroutines.resume

class LoginActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySystemBars(this, Ui.SURFACE)
        setContentView(buildView())
        val saved = AccountStore(this).email
        saved?.let { emailInput.setText(it) }
        // D-pad / TV: start in the e-mail field, or in the password field when the e-mail is remembered.
        Tv.initialFocus(if (saved.isNullOrBlank()) emailInput else passwordInput)
    }

    private fun buildView(): View {
        val tv = Tv.isTv(this)
        // Short screens (TV is ~540dp tall in landscape): smaller logo so the form fits.
        val short = resources.configuration.screenHeightDp in 1 until 640
        val col = Tv.maxWidthColumn(this, 480).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(if (short) 20 else 48), dp(28), dp(28))
        }

        col.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(if (short) 96 else 150), dp(if (short) 96 else 150)))

        col.addView(text(getString(R.string.zfy_app_name), 28f, Ui.BLUE_DARK, true).apply {
            gravity = Gravity.CENTER
        })
        col.addView(text(getString(R.string.zfy_login_welcome), 14f, Ui.TEXT_SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(if (short) 16 else 28))
        })

        emailInput = input(getString(R.string.zfy_email_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            // 下一项 on the (TV) keyboard jumps to the password field.
            imeOptions = EditorInfo.IME_ACTION_NEXT or (if (tv) EditorInfo.IME_FLAG_NO_EXTRACT_UI else 0)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_NEXT || isEnter(ev)) { passwordInput.requestFocus(); true } else false
            }
        }
        passwordInput = input(getString(R.string.zfy_password_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE or (if (tv) EditorInfo.IME_FLAG_NO_EXTRACT_UI else 0)
            // 完成 / Enter submits the login.
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_GO || isEnter(ev)) {
                    hideKeyboard(this); doLogin(); true
                } else false
            }
        }
        col.addView(emailInput, fullWidth(dp(52)))
        col.addView(passwordInput, fullWidth(dp(52)).apply { topMargin = dp(12) })

        loginButton = primaryButton(getString(R.string.zfy_login)).apply { setOnClickListener { doLogin() } }
        Tv.focusRing(loginButton, 24f)
        col.addView(loginButton, fullWidth(dp(52)).apply { topMargin = dp(24) })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        col.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(16) })

        status = text("", 13f, Ui.TEXT_SUB).apply { gravity = Gravity.CENTER }
        col.addView(status, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val links = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val register = outlineButton(getString(R.string.zfy_register)).apply {
            setOnClickListener { openUrl(PanelConfig.REGISTER_URL) }
            Tv.focusRing(this, 24f)
        }
        val forgot = outlineButton(getString(R.string.zfy_forgot)).apply {
            setOnClickListener { openUrl(PanelConfig.RESET_URL) }
            Tv.focusRing(this, 24f)
        }
        links.addView(register, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        links.addView(forgot, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        // D-pad order: 邮箱 → 密码 → 登录 → 注册 / 忘记密码
        Tv.chainVertical(emailInput, passwordInput, loginButton, register)
        forgot.nextFocusUpId = Tv.ensureId(loginButton)
        col.addView(links, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })

        col.addView(text(getString(R.string.zfy_attribution), 11f, Ui.TEXT_SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(Ui.SURFACE)
            isFillViewport = true
            fitsSystemWindows = true
            addView(col, Tv.centeredInFrame())
        }
    }

    private fun isEnter(ev: KeyEvent?): Boolean =
        ev != null && ev.action == KeyEvent.ACTION_DOWN &&
            (ev.keyCode == KeyEvent.KEYCODE_ENTER || ev.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT_HINT)
        textSize = 16f
        setPadding(dp(16), 0, dp(16), 0)
        background = Ui.rounded(Ui.INPUT_BG, dp(12).toFloat())
        Tv.focusRing(this, 12f, scale = 1f)
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
                        val code = askTotp()
                        if (code.isNullOrBlank()) {
                            setBusy(false, getString(R.string.zfy_mfa_unsupported)); return@launch
                        }
                        val r2 = withContext(Dispatchers.IO) { client.submitTotp(code) }
                        if (r2 is PanelClient.LoginResult.Failed) {
                            setBusy(false, r2.message); return@launch
                        }
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

    private suspend fun askTotp(): String? = suspendCancellableCoroutine { cont ->
        val input = EditText(this).apply {
            hint = getString(R.string.zfy_totp_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        var done = false
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.zfy_totp_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (!done) { done = true; cont.resume(input.text.toString()) }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (!done) { done = true; cont.resume(null) }
            }
            .setOnCancelListener {
                if (!done) { done = true; cont.resume(null) }
            }
            .show()
        input.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_DONE || isEnter(ev)) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick(); true
            } else false
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
