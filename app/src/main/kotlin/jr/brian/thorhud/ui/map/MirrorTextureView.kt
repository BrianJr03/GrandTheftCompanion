package jr.brian.thorhud.ui.map

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * TextureView (not SurfaceView) so the crop is a transform matrix:
 * zero pixel copies, and the view animates with the pager as it slides.
 */
internal class MirrorTextureView(context: Context) :
    TextureView(context), TextureView.SurfaceTextureListener {

    var captureSize: Size = Size(1, 1)
    var onSurfaceReady: ((Surface) -> Unit)? = null
    var onSurfaceGone: ((Surface) -> Unit)? = null

    private val crop = RectF(0f, 0f, 1f, 1f)
    private val transformMatrix = Matrix()
    private var surface: Surface? = null

    init {
        surfaceTextureListener = this
    }

    fun setCrop(left: Float, top: Float, right: Float, bottom: Float) {
        crop.set(left, top, right, bottom)
        applyCrop()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        // Buffers default to view size; without this the capture lands
        // in a downscaled buffer and the minimap blurs before it's
        // magnified.
        texture.setDefaultBufferSize(captureSize.width, captureSize.height)
        val created = Surface(texture)
        surface = created
        applyCrop()
        onSurfaceReady?.invoke(created)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        // TextureView resets buffer size to view size on resize; put it back.
        texture.setDefaultBufferSize(captureSize.width, captureSize.height)
        applyCrop()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        surface?.let { old ->
            onSurfaceGone?.invoke(old)
            old.release()
        }
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCrop()
    }

    private fun applyCrop() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || crop.isEmpty) return
        val source = RectF(
            crop.left * viewWidth,
            crop.top * viewHeight,
            crop.right * viewWidth,
            crop.bottom * viewHeight,
        )
        val target = RectF(0f, 0f, viewWidth, viewHeight)
        transformMatrix.setRectToRect(source, target, Matrix.ScaleToFit.FILL)
        setTransform(transformMatrix)
    }
}
