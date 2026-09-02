package net.ripster.mobile.ui.login

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Окно входа в сервис прямо в приложении — без DevTools и копипаста заголовков.
 * Открывает страницу входа сервиса в WebView; как только после успешного входа
 * появляется нужная кука (или токен во фрагменте адреса) — закрывается и
 * возвращает значение вызывающему.
 *
 * Extras: [EXTRA_SERVICE] = "soundcloud" | "deezer" | "yandex".
 * Result OK: [EXTRA_TOKEN] — снятый токен/ARL/oauth_token.
 */
class LoginWebViewActivity : Activity() {

    private lateinit var web: WebView
    private var target: LoginTarget? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val svc = intent.getStringExtra(EXTRA_SERVICE).orEmpty()
        val t = LoginTargets.byService(svc)
        if (t == null) { finish(); return }
        target = t

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E12"))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        bar.addView(TextView(this).apply {
            text = "Вход · ${t.title}"
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#FF4D8D"))
            textSize = 14f
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { cancel() }
        })
        root.addView(bar)

        val hint = TextView(this).apply {
            text = t.hint
            setTextColor(Color.parseColor("#9A9AA6"))
            textSize = 11f
            setPadding(dp(14), 0, dp(14), dp(8))
        }
        root.addView(hint)

        web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        root.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgentString.replace("; wv", "")   // не «webview»
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                checkUrl(url)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                checkUrl(url)
                checkCookies()
            }
        }
        web.loadUrl(t.url)

        // Deezer/SoundCloud логинятся XHR-ом без перезагрузки страницы —
        // onPageFinished не сработает. Опрашиваем куку сами (как ПК, 1.5с).
        poll()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun poll() {
        if (done || isFinishing) return
        checkUrl(web.url)
        checkCookies()
        handler.postDelayed({ poll() }, 1500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** OAuth implicit: токен во фрагменте `#...access_token=XXX`. */
    private fun checkUrl(url: String?) {
        val t = target ?: return
        val param = t.urlTokenParam ?: return
        if (url == null || done) return
        val frag = url.substringAfter('#', "")
        val q = (frag + "&" + url.substringAfter('?', "")).split('&', ';')
        val v = q.firstOrNull { it.startsWith("$param=") }?.substringAfter("=")
            ?.let { android.net.Uri.decode(it) }
        if (!v.isNullOrBlank()) succeed(v)
    }

    private fun checkCookies() {
        val t = target ?: return
        val name = t.cookieName ?: return
        if (done) return
        val cm = CookieManager.getInstance()
        val hosts = listOf("https://${t.domain}", "https://www.${t.domain}", "https://secure.${t.domain}")
        for (h in hosts) {
            val jar = cm.getCookie(h) ?: continue
            val v = jar.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }?.substringAfter("=")
            // arl — 192 hex-символа; oauth_token — с точкой. Отсекаем пустышки.
            if (!v.isNullOrBlank() && v.length >= 16 && v != "deleted") { succeed(v); return }
        }
    }

    private fun succeed(token: String) {
        done = true
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
        finish()
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else cancel()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SERVICE = "service"
        const val EXTRA_TOKEN = "token"
    }
}
