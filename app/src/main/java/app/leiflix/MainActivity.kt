@file:Suppress("UnstableApiUsage", "UnsafeOptInUsageError")

package app.leiflix

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var embedWebView: WebView? = null
    private var streamYaCapturado = false
    private var loadingDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawableResource(android.R.color.black)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        if (packageManager.hasSystemFeature("android.software.leanback")) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }

        webView = findViewById(R.id.webview)
        setupWebView()

        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (loadingDialog?.isShowing == true) {
                    ocultarLoading()
                    embedWebView?.destroy()
                    embedWebView = null
                    streamYaCapturado = false
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finishAffinity()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        webView.loadUrl("https://leiflix.vercel.app/private/private.html")
    }

    // -----------------------------------------------------------------------
    // Dialog de carga 100% por código — sin XMLs extra
    // -----------------------------------------------------------------------
    private fun mostrarLoading() {
        if (loadingDialog?.isShowing == true) return

        val isTv = packageManager.hasSystemFeature("android.software.leanback")

        // Fondo redondeado del dialog
        val bgShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 48f
            setColor("#1a2535".toColorInt())
            setStroke(2, "#2a3a50".toColorInt())
        }

        // Spinner
        val spinnerSize = if (isTv) 128 else 96
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                "#58a6ff".toColorInt()
            )
            layoutParams = LinearLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        // Texto principal
        val textoPrincipal = TextView(this).apply {
            setText(R.string.loading_stream)
            setTextColor("#58a6ff".toColorInt())
            textSize = if (isTv) 26f else 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        // Subtexto
        val textoSecundario = TextView(this).apply {
            setText(R.string.loading_stream_subtitle)
            setTextColor("#8b949e".toColorInt())
            textSize = if (isTv) 15f else 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        // Contenedor principal
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 64, 80, 64)
            background = bgShape
            addView(spinner)
            addView(textoPrincipal)
            addView(textoSecundario)
        }

        loadingDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(container)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            val ancho = when {
                resources.displayMetrics.widthPixels >= 1920 ->
                    (resources.displayMetrics.widthPixels * 0.35).toInt()
                resources.displayMetrics.widthPixels >= 1280 ->
                    (resources.displayMetrics.widthPixels * 0.45).toInt()
                else ->
                    (resources.displayMetrics.widthPixels * 0.75).toInt()
            }
            window?.setLayout(
                ancho,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setCancelable(false)
        }
        loadingDialog?.show()
    }

    private fun ocultarLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    // -----------------------------------------------------------------------
    // Setup del WebView principal
    // -----------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.BLACK)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.webViewClient = LeiflixClient()

        webView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val orientation = resources.configuration.orientation
            val js = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                "(() => { const v=document.querySelector('video'); if(v&&v.requestFullscreen) v.requestFullscreen(); })();"
            } else {
                "(() => { if(document.fullscreenElement) document.exitFullscreen(); })();"
            }
            webView.evaluateJavascript(js, null)
        }
    }

    // -----------------------------------------------------------------------
    // Lanza un WebView invisible para cazar el .m3u8 de una página embed
    // -----------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun lanzarEmbedInterceptor(embedUrl: String) {

        embedWebView?.destroy()
        embedWebView = null
        streamYaCapturado = false

        mostrarLoading()

        // Timeout: ocultar loading si no se captura stream en 10 segundos
        android.os.Handler(mainLooper).postDelayed({
            if (!streamYaCapturado) {
                ocultarLoading()
                embedWebView?.destroy()
                embedWebView = null
                streamYaCapturado = false
                Toast.makeText(this, "No se pudo obtener el stream", Toast.LENGTH_LONG).show()
            }
        }, 15_000)

        CookieManager.getInstance().setAcceptCookie(true)

        embedWebView = WebView(this).apply {

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true
                setSupportMultipleWindows(false)
                userAgentString = "Mozilla/5.0 (Linux; Android 14) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url.toString()

                    if (!streamYaCapturado && esUrlStream(url)) {
                        streamYaCapturado = true

                        val headers = request.requestHeaders.toMutableMap()

                        val cookies = CookieManager.getInstance().getCookie(url)
                        if (!cookies.isNullOrEmpty()) {
                            headers["Cookie"] = cookies
                        }

                        if (!headers.containsKey("Referer")) {
                            headers["Referer"] = embedUrl
                        }

                        runOnUiThread {
                            ocultarLoading()
                            lanzarPlayer(url, headers)
                            view.stopLoading()
                            embedWebView?.destroy()
                            embedWebView = null
                        }
                    }

                    return null
                }
            }

            loadUrl(embedUrl)
        }
    }

    // -----------------------------------------------------------------------
    // Lanza PlayerActivity con la URL y los headers capturados
    // -----------------------------------------------------------------------
    private fun lanzarPlayer(url: String, headers: Map<String, String> = emptyMap()) {
        val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
            putExtra("videoUrl", url)
            headers.forEach { (key, value) ->
                putExtra("header_$key", value)
            }
        }
        startActivity(intent)
    }

    // -----------------------------------------------------------------------
    // Detecta si una URL es un stream de video
    // -----------------------------------------------------------------------
    private fun esUrlStream(url: String): Boolean {
        if (url.contains(".m3u8") || url.contains(".m3u")) return true
        if (url.endsWith(".ts")) return true
        if (url.contains("/play/")) return true
        if (Regex(".*/live/[^/]+/[^/]+/.*\\.ts$").containsMatchIn(url)) return true
        if (Regex(".*/live/[^/]+/[^/]+/\\d+.*").containsMatchIn(url)) return true
        if (Regex(".*/movie/[^/]+/[^/]+/\\d+.*").containsMatchIn(url)) return true
        if (Regex(".*/series/[^/]+/[^/]+/\\d+.*").containsMatchIn(url)) return true
        return false
    }

    // -----------------------------------------------------------------------
    // Detecta si una URL es una página embed (no un stream directo)
    // -----------------------------------------------------------------------
    private fun esUrlEmbed(url: String): Boolean {
        if (esUrlStream(url)) return false
        if (url.startsWith("file://") || url.contains("leiflix")) return false

        return url.contains("tvporinternet") ||
                url.contains("embed.saohgdasregions") ||
                url.contains("cablevisionhd") ||
                url.contains("futbollibrehd") ||
                url.contains("pirlotvonline") ||
                url.endsWith(".php") ||
                url.contains("/embed/") ||
                (url.startsWith("http") && !url.contains("leiflix.vercel.app"))
    }

    // -----------------------------------------------------------------------
    // WebViewClient principal de LEIFLIX
    // -----------------------------------------------------------------------
    @Suppress("DEPRECATION")
    inner class LeiflixClient : WebViewClient() {

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val cleanCSS = """
                (function() {
                    var style = document.createElement('style');
                    style.innerHTML = 'html,body{overflow:auto!important;-ms-overflow-style:none!important;scrollbar-width:none!important;}::-webkit-scrollbar{display:none!important;width:0!important;height:0!important;}';
                    document.head.appendChild(style);
                })();
            """.trimIndent()
            view?.evaluateJavascript(cleanCSS, null)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url.toString()
            return handleUrl(url)
        }

        private fun handleUrl(url: String): Boolean {
            return when {

                url.startsWith("acestream://") -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "AceStream no instalado",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    true
                }

                esUrlStream(url) -> {
                    lanzarPlayer(url)
                    true
                }

                esUrlEmbed(url) -> {
                    lanzarEmbedInterceptor(url)
                    true
                }

                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocultarLoading()
        embedWebView?.destroy()
        embedWebView = null
    }
}