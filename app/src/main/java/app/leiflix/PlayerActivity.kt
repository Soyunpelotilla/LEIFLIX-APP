@file:Suppress("UnstableApiUsage", "UnsafeOptInUsageError")

package app.leiflix

import android.os.Bundle
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

    private fun esStreamHls(url: String): Boolean {
        return url.contains(".m3u8") ||
                url.contains(".m3u") ||
                url.contains("playlist") ||
                url.contains("/live/") ||
                url.contains("/movie/") ||
                url.contains("/series/")
    }

    private fun initializePlayer() {
        // 1. Recogemos la URL que manda el intent
        val urlRaw = intent.getStringExtra("videoUrl") ?: return

        // 2. Aplicamos la lógica de #noSeek
        val esLive = urlRaw.contains("#noSeek")
        val url = urlRaw.replace("#noSeek", "")

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

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setRenderersFactory { eventHandler, video, audio, text, metadata ->
                androidx.media3.exoplayer.DefaultRenderersFactory(this)
                    .setExtensionRendererMode(
                        androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    )
                    .setEnableDecoderFallback(true)
                    .createRenderers(eventHandler, video, audio, text, metadata)
            }
            .build().apply {
                playerView.player = this
                playerView.controllerAutoShow = false
                playerView.controllerShowTimeoutMs = 3000
                playerView.hideController()
                playerView.requestFocus()

                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

                val dataSourceFactory: HttpDataSource.Factory =
                    DefaultHttpDataSource.Factory()
                        .setUserAgent(headersCapturados["User-Agent"] ?: userAgent)
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(15_000)
                        .setDefaultRequestProperties(headersCapturados)

                val mediaSource = if (esStreamHls(url)) {
                    HlsMediaSource.Factory(dataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(MediaItem.fromUri(url))
                } else {
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(url))
                }

                setMediaSource(mediaSource)
                prepare()

                // Si es LIVE, no hacemos seek a la posición anterior
                if (!esLive && lastPosition > 0) seekTo(lastPosition)
                
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val fallbackSource = if (esStreamHls(url)) {
                            ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(url))
                        } else {
                            HlsMediaSource.Factory(dataSourceFactory)
                                .setAllowChunklessPreparation(true)
                                .createMediaSource(MediaItem.fromUri(url))
                        }
                        if (error.cause is IOException) {
                            playerView.postDelayed({
                                if (player != null) {
                                    setMediaSource(fallbackSource)
                                    prepare()
                                    play()
                                }
                            }, 2000)
                        } else {
                            setMediaSource(fallbackSource)
                            prepare()
                            play()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            playerView.requestFocus()

                            // Si es LIVE, no intentamos ir al segundo 0 (deja que cargue el directo)
                            if (!seekInicialHecho && !esLive) {
                                seekInicialHecho = true
                                seekTo(0)
                            }
                        }
                    }
                })
            }
    }

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
