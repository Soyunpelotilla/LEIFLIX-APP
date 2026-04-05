@file:Suppress("UnstableApiUsage", "UnsafeOptInUsageError")

package app.leiflix

import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import java.io.IOException

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var lastPosition: Long = 0L
    private var seekInicialHecho = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.black)

        setContentView(R.layout.activity_player)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }

        playerView = findViewById(R.id.player_view)
        initializePlayer()

        onBackPressedDispatcher.addCallback(this) {
            releasePlayer()
            finish()
        }
    }

    // ──────────────────────────────────────────────
    // Recoge headers extra del Intent (header_X-Something → X-Something)
    // ──────────────────────────────────────────────
    private fun recogerHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        intent.extras?.keySet()?.forEach { key ->
            if (key.startsWith("header_")) {
                val headerName = key.removePrefix("header_")
                val headerValue = intent.getStringExtra(key)
                if (!headerValue.isNullOrEmpty()) {
                    headers[headerName] = headerValue
                }
            }
        }
        return headers
    }

    // ──────────────────────────────────────────────
    // Detectores de tipo de stream
    // ──────────────────────────────────────────────
    private fun esStreamHls(url: String): Boolean {
        return url.contains(".m3u8") ||
                url.contains(".m3u") ||
                url.contains("playlist") ||
                url.contains("/live/") ||
                url.contains("/movie/") ||
                url.contains("/series/")
    }

    private fun esStreamDash(url: String): Boolean {
        return url.contains(".mpd")
    }

    // ──────────────────────────────────────────────
    // Convierte HEX → Base64URL (necesario para ClearKey DRM)
    // ──────────────────────────────────────────────
    private fun hexToBase64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
            .trimEnd('=')
    }

    // ──────────────────────────────────────────────
    // Construye el MediaItem con o sin DRM
    // ──────────────────────────────────────────────
    private fun buildMediaItem(url: String, kidHex: String?, keyHex: String?): MediaItem {
        return if (esStreamDash(url) && !kidHex.isNullOrEmpty() && !keyHex.isNullOrEmpty()) {
            // DASH + ClearKey DRM
            val licenseJson = """
                {"keys":[{"kty":"oct","kid":"${hexToBase64Url(kidHex)}","k":"${hexToBase64Url(keyHex)}"}],"type":"temporary"}
            """.trimIndent()
            val licenseUri = "data:application/json;base64," +
                    Base64.encodeToString(licenseJson.toByteArray(), Base64.NO_WRAP)

            MediaItem.Builder()
                .setUri(url)
                .setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                        .setLicenseUri(licenseUri)
                        .build()
                )
                .build()
        } else {
            // Sin DRM
            MediaItem.fromUri(url)
        }
    }

    // ──────────────────────────────────────────────
    // Inicializa el player
    // ──────────────────────────────────────────────
    private fun initializePlayer() {
        val urlRaw = intent.getStringExtra("videoUrl") ?: return
        val esLive = urlRaw.contains("#noSeek")
        val url = urlRaw.replace("#noSeek", "")

        // Claves DRM opcionales
        val kidHex = intent.getStringExtra("kid")
        val keyHex = intent.getStringExtra("key")

        val headersCapturados = recogerHeaders()

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_000, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val dataSourceFactory: HttpDataSource.Factory =
            DefaultHttpDataSource.Factory()
                .setUserAgent(headersCapturados["User-Agent"] ?: userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(headersCapturados)

        // Construye el MediaSource según el tipo de stream
        fun crearMediaSource(targetUrl: String = url, forzarHls: Boolean = false, forzarProgressive: Boolean = false) =
            when {
                !forzarHls && !forzarProgressive && esStreamDash(targetUrl) -> {
                    DashMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(buildMediaItem(targetUrl, kidHex, keyHex))
                }
                !forzarProgressive && (forzarHls || esStreamHls(targetUrl)) -> {
                    HlsMediaSource.Factory(dataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(buildMediaItem(targetUrl, null, null))
                }
                else -> {
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(buildMediaItem(targetUrl, null, null))
                }
            }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setRenderersFactory(
                androidx.media3.exoplayer.DefaultRenderersFactory(this)
                    .setExtensionRendererMode(
                        androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    )
                    .setEnableDecoderFallback(true)
            )
            .build().apply {
                playerView.player = this
                playerView.controllerAutoShow = false
                playerView.controllerShowTimeoutMs = 3000
                playerView.hideController()
                playerView.requestFocus()

                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

                setMediaSource(crearMediaSource())
                prepare()

                if (!esLive && lastPosition > 0) seekTo(lastPosition)
                playWhenReady = true

                addListener(object : Player.Listener {

                    // Fallback: si falla, prueba con el otro formato
                    override fun onPlayerError(error: PlaybackException) {
                        val fallback = when {
                            esStreamDash(url) -> crearMediaSource(forzarHls = true)
                            esStreamHls(url)  -> crearMediaSource(forzarProgressive = true)
                            else              -> crearMediaSource(forzarHls = true)
                        }
                        val delay = if (error.cause is IOException) 2000L else 0L
                        playerView.postDelayed({
                            if (player != null) {
                                setMediaSource(fallback)
                                prepare()
                                play()
                            }
                        }, delay)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            playerView.requestFocus()
                            if (!seekInicialHecho && !esLive) {
                                seekInicialHecho = true
                                seekTo(0)
                            }
                        }
                    }
                })
            }
    }

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            lastPosition = it.currentPosition
            it.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}
