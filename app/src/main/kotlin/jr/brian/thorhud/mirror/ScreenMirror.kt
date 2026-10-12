package jr.brian.thorhud.mirror

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the default display (the top screen, where GTA IV runs) into
 * a Surface via MediaProjection, so the map page can show the game's
 * own minimap enlarged. All methods must be called on the main thread.
 */
@Singleton
class ScreenMirror @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Status { Idle, Starting, Active }

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _captureSize = MutableStateFlow<Size?>(null)
    val captureSize: StateFlow<Size?> = _captureSize.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    // Tracks whether we owe the service a stopService(); release() uses
    // it so the service is stopped exactly once no matter how the
    // session ends.
    private var serviceRequested = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped by the system")
            release()
        }
    }

    fun createConsentIntent(): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        // Pin to the default display: Android 14+ otherwise offers a
        // "single app" option, which would miss the game.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay(),
            )
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    fun start(resultCode: Int, data: Intent) {
        if (_status.value != Status.Idle) return
        _status.value = Status.Starting
        serviceRequested = true
        ContextCompat.startForegroundService(
            context,
            MirrorService.startIntent(context, resultCode, data),
        )
    }

    internal fun onProjectionStarted(newProjection: MediaProjection) {
        teardown()

        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null) {
            Log.w(TAG, "no default display to mirror")
            newProjection.stop()
            release()
            return
        }
        // getRealMetrics(context) returns the context's display size,
        // not Display.DEFAULT_DISPLAY's — HudActivity runs on the
        // bottom panel so it'd mis-size the virtual display and the
        // top-screen content would arrive letterboxed. Use the mode's
        // physical dimensions and apply rotation explicitly.
        val mode = display.mode
        val landscape = display.rotation == Surface.ROTATION_90 ||
            display.rotation == Surface.ROTATION_270
        val size = if (landscape) {
            Size(mode.physicalHeight, mode.physicalWidth)
        } else {
            Size(mode.physicalWidth, mode.physicalHeight)
        }
        val densityDpi = context.resources.displayMetrics.densityDpi

        // Android 14+ requires the callback before createVirtualDisplay,
        // and allows only one virtual display per consent — create it
        // here, then swap the surface in/out as the view comes and goes.
        newProjection.registerCallback(projectionCallback, mainHandler)
        val created = try {
            newProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                size.width,
                size.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "createVirtualDisplay refused", e)
            null
        }
        if (created == null) {
            newProjection.unregisterCallback(projectionCallback)
            newProjection.stop()
            release()
            return
        }

        projection = newProjection
        virtualDisplay = created
        _captureSize.value = size
        _status.value = Status.Active
        Log.i(TAG, "mirroring default display at ${size.width}x${size.height}")
    }

    internal fun onProjectionFailed() {
        release()
    }

    fun attach(newSurface: Surface) {
        surface = newSurface
        virtualDisplay?.surface = newSurface
    }

    fun detach(oldSurface: Surface) {
        if (surface !== oldSurface) return
        surface = null
        virtualDisplay?.surface = null
    }

    fun stop() {
        val active = projection
        if (active != null) {
            // Unregister first: stop() posts onStop() to the main
            // thread, where it could otherwise land after a new session
            // has started and tear that one down instead.
            active.unregisterCallback(projectionCallback)
            active.stop()
        }
        release()
    }

    private fun teardown() {
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.unregisterCallback(projectionCallback)
        projection = null
    }

    private fun release() {
        teardown()
        _captureSize.value = null
        _status.value = Status.Idle
        if (serviceRequested) {
            serviceRequested = false
            context.stopService(Intent(context, MirrorService::class.java))
        }
    }

    private companion object {
        const val TAG = "ScreenMirror"
        const val VIRTUAL_DISPLAY_NAME = "gtc-minimap-mirror"
    }
}
