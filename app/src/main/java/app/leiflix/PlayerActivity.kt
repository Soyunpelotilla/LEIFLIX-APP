@file:Suppress("UnstableApiUsage", "UnsafeOptInUsageError")

package app.leiflix

import android.graphics.Color
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
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.IOException

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var lastPosition: Long = 0L
    private var seekInicialHecho = false

    // Zoom: cicla entre fit → zoom → fill
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

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

        // Zoom con long press
        playerView.setOnLongClickListener {
            toggleZoom()
            true
        }

        initializePlayer()

        onBackPressedDispatcher.addCallback(this) {
            releasePlayer()
            finish()
        }
    }

    // ──────────────────────────────────────────────
    // Zoom: fit → zoom → fill → fit …
    // ──────────────────────────────────────────────
    private fun toggleZoom() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT  -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else                                    -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = currentResizeMode
    }

    // ──────────────────────────────────────────────
    // Recoge headers extra del Intent
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
    private fun esStreamHls(url: String) =
        url.contains(".m3u8") || url.contains(".m3u") ||
        url.contains("playlist") || url.contains("/live/") ||
        url.contains("/movie/") || url.contains("/series/")

    private fun esStreamDash(url: String) = url.contains(".mpd")

    // ──────────────────────────────────────────────
    // Hex → Base64URL (para construir el JSON ClearKey)
    // ──────────────────────────────────────────────
    private fun hexToBase64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
    }

    // ──────────────────────────────────────────────
    // DRM ClearKey via LocalMediaDrmCallback (robusto, sin pantalla negra)
    // ──────────────────────────────────────────────
    private fun buildClearKeyDrmManager(kidHex: String, keyHex: String): DefaultDrmSessionManager {
        val psshJson = """{"keys":[{"kty":"oct","kid":"${hexToBase64Url(kidHex)}","k":"${hexToBase64Url(keyHex)}"}],"type":"temporary"}"""
        val drmCallback = LocalMediaDrmCallback(psshJson.toByteArray())
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setPlayClearSamplesWithoutKeys(true)
            .setMultiSession(false)
            .build(drmCallback)
    }

    // ──────────────────────────────────────────────
    // Inicializa el player
    // ──────────────────────────────────────────────
    private fun initializePlayer() {
        val urlRaw = intent.getStringExtra("videoUrl") ?: return
        val esLive  = urlRaw.contains("#noSeek")
        val url     = urlRaw.replace("#noSeek", "")

        val kidHex   = intent.getStringExtra("kid") ?: ""
        val keyHex   = intent.getStringExtra("key") ?: ""
        val tieneDrm = esStreamDash(url) && kidHex.isNotEmpty() && keyHex.isNotEmpty()

        val headersCapturados = recogerHeaders()

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
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
            .build()

        playerView.player = player
        playerView.controllerAutoShow = false
        playerView.controllerShowTimeoutMs = 3000
        playerView.hideController()
        playerView.requestFocus()
        playerView.keepScreenOn = true
        playerView.setShutterBackgroundColor(Color.TRANSPARENT)

        val mediaSource = when {
            tieneDrm -> {
                val drmManager = buildClearKeyDrmManager(kidHex, keyHex)
                DashMediaSource.Factory(dataSourceFactory)
                    .setDrmSessionManagerProvider { drmManager }
                    .createMediaSource(MediaItem.fromUri(url))
            }
            esStreamDash(url) -> {
                DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))
            }
            esStreamHls(url) -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(url))
            }
            else -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))
            }
        }

        player!!.apply {
            setMediaSource(mediaSource)
            prepare()
            if (!esLive && lastPosition > 0) seekTo(lastPosition)
            playWhenReady = true

            addListener(object : Player.Listener {

                override fun onPlayerError(error: PlaybackException) {
                    if (tieneDrm) return
                    val fallback = when {
                        esStreamDash(url) -> HlsMediaSource.Factory(dataSourceFactory)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(MediaItem.fromUri(url))
                        esStreamHls(url) -> ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url))
                        else -> HlsMediaSource.Factory(dataSourceFactory)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(MediaItem.fromUri(url))
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

    override fun onStop() {
        super.onStop()
        releasePlayer()
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
