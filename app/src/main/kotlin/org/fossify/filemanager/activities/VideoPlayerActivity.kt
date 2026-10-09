package org.fossify.filemanager.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.ActivityVideoPlayerBinding
import org.fossify.filemanager.extensions.openPath
import org.fossify.filemanager.helpers.OPEN_AS_VIDEO
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Simple built-in video viewer (similar to Total Commander's F3 viewer):
 * full screen, tap to show/hide controls, seek bar, play/pause, position is kept on rotation.
 */
class VideoPlayerActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PATH = "video_path"
        private const val KEY_POSITION = "KEY_POSITION"
        private const val KEY_PLAY_WHEN_READY = "KEY_PLAY_WHEN_READY"
        private const val KEY_FULLSCREEN = "KEY_FULLSCREEN"
        private const val POSITIONS_PREFS = "video_positions"
        private const val MIN_SAVED_POSITION_MS = 3_000L
        private const val END_THRESHOLD_MS = 5_000L
        private const val VOLUME_INDICATOR_HIDE_DELAY_MS = 800L

        fun start(context: Context, path: String) {
            val intent = Intent(context, VideoPlayerActivity::class.java).putExtra(EXTRA_PATH, path)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var videoPath = ""
    private var videoUri: Uri? = null
    private var startPosition = 0L
    private var playWhenReady = true
    private var isFullscreen = false
    private lateinit var insetsController: WindowInsetsControllerCompat
    private lateinit var audioManager: AudioManager

    // Volume swipe state
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartVolume = 0
    private var volumeSwipeActive = false
    private val hideVolumeIndicator = Runnable { binding.videoVolumeIndicator.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoPath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        videoUri = when {
            videoPath.isNotEmpty() -> Uri.fromFile(File(videoPath))
            else -> intent.data
        }

        if (videoUri == null) {
            finish()
            return
        }

        binding.videoTitle.text = if (videoPath.isNotEmpty()) {
            File(videoPath).name
        } else {
            videoUri?.lastPathSegment.orEmpty()
        }

        savedInstanceState?.let {
            startPosition = it.getLong(KEY_POSITION, 0L)
            playWhenReady = it.getBoolean(KEY_PLAY_WHEN_READY, true)
            isFullscreen = it.getBoolean(KEY_FULLSCREEN, false)
        }

        // Fresh launch: continue from the position saved when the video was closed last time
        if (savedInstanceState == null) {
            startPosition = loadSavedPosition()
        }

        setupFullscreen()
        setupControlsVisibility()
        setupFullscreenButton()
        setupVolumeGesture()
        applyFullscreen(isFullscreen, changeOrientation = false)

        // Back leaves fullscreen first, the next back closes the player
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    applyFullscreen(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            startPosition = it.currentPosition
            playWhenReady = it.playWhenReady
        }
        outState.putLong(KEY_POSITION, startPosition)
        outState.putBoolean(KEY_PLAY_WHEN_READY, playWhenReady)
        outState.putBoolean(KEY_FULLSCREEN, isFullscreen)
    }

    private fun initPlayer() {
        val uri = videoUri ?: return
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        binding.videoPlayerView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Codec/container is not supported by the built-in player: fall back to external apps
                Toast.makeText(this@VideoPlayerActivity, R.string.video_play_error, Toast.LENGTH_SHORT).show()
                if (videoPath.isNotEmpty()) {
                    openPath(videoPath, true, OPEN_AS_VIDEO)
                }
                finish()
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.seekTo(startPosition)
        exoPlayer.playWhenReady = playWhenReady
        exoPlayer.prepare()
    }

    private fun releasePlayer() {
        binding.videoVolumeIndicator.removeCallbacks(hideVolumeIndicator)
        player?.let {
            val ended = it.playbackState == Player.STATE_ENDED
            startPosition = if (ended) 0L else it.currentPosition
            playWhenReady = it.playWhenReady
            savePosition(it.currentPosition, it.duration, ended)
            it.release()
        }
        player = null
        binding.videoPlayerView.player = null
    }

    private fun positionKey() = videoPath.ifEmpty { videoUri?.toString().orEmpty() }

    private fun loadSavedPosition(): Long {
        val key = positionKey()
        if (key.isEmpty()) return 0L
        return getSharedPreferences(POSITIONS_PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
    }

    private fun savePosition(position: Long, duration: Long, ended: Boolean) {
        val key = positionKey()
        if (key.isEmpty()) return

        val nearEnd = duration != C.TIME_UNSET && duration > 0 && position >= duration - END_THRESHOLD_MS
        val editor = getSharedPreferences(POSITIONS_PREFS, Context.MODE_PRIVATE).edit()
        if (ended || nearEnd || position < MIN_SAVED_POSITION_MS) {
            // Finished or barely started: next time play from the beginning
            editor.remove(key)
        } else {
            editor.putLong(key, position)
        }
        editor.apply()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Keep the title bar below the status bar / cutout
        ViewCompat.setOnApplyWindowInsetsListener(binding.videoTitle) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + v.paddingBottom, bars.top + v.paddingBottom, bars.right + v.paddingBottom, v.paddingBottom)
            insets
        }
    }

    private fun setupControlsVisibility() {
        insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // The title follows the playback controls; system bars are controlled by fullscreen mode only
        binding.videoPlayerView.setControllerVisibilityListener(
            androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                binding.videoTitle.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
            }
        )
    }

    private fun setupFullscreenButton() {
        // Shows the fullscreen button in the player controls
        binding.videoPlayerView.setFullscreenButtonClickListener { fullscreen ->
            applyFullscreen(fullscreen)
        }
    }

    /**
     * Fullscreen: system bars hidden (swipe from the edge shows them temporarily) and
     * landscape orientation for wide videos. Normal mode: bars visible, free rotation.
     */
    private fun applyFullscreen(fullscreen: Boolean, changeOrientation: Boolean = true) {
        isFullscreen = fullscreen
        binding.videoPlayerView.setFullscreenButtonState(fullscreen)

        if (fullscreen) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }

        if (changeOrientation) {
            requestedOrientation = if (fullscreen && isWideVideo()) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    /**
     * Vertical swipe on the right half of the screen changes the media volume.
     * A plain tap is not consumed, so it still shows/hides the player controls.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupVolumeGesture() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        binding.videoPlayerView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    touchStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    volumeSwipeActive = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchStartX
                    val dy = touchStartY - event.y // swipe up = positive
                    if (!volumeSwipeActive && touchStartX > view.width / 2f && abs(dy) > slop * 2 && abs(dy) > abs(dx)) {
                        volumeSwipeActive = true
                    }

                    if (volumeSwipeActive) {
                        changeVolume(dy, view.height)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (volumeSwipeActive) {
                        volumeSwipeActive = false
                        binding.videoVolumeIndicator.postDelayed(hideVolumeIndicator, VOLUME_INDICATOR_HIDE_DELAY_MS)
                        true
                    } else {
                        false
                    }
                }

                else -> volumeSwipeActive
            }
        }
    }

    private fun changeVolume(swipeDistance: Float, viewHeight: Int) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // A swipe over ~80% of the screen height covers the whole volume range
        val delta = swipeDistance / (viewHeight * 0.8f) * maxVolume
        val newVolume = (touchStartVolume + delta).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

        binding.videoVolumeIndicator.apply {
            removeCallbacks(hideVolumeIndicator)
            text = getString(R.string.video_volume, newVolume * 100 / maxVolume)
            visibility = View.VISIBLE
        }
    }

    private fun isWideVideo(): Boolean {
        val size = player?.videoSize ?: return true
        return size.width == 0 || size.width >= size.height
    }
}
