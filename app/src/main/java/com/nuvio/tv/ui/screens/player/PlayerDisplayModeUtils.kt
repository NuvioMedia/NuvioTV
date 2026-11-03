import android.content.Context
import androidx.media3.ui.AspectRatioFrameLayout
import com.nuvio.tv.R

internal object PlayerDisplayModeUtils {
    fun nextResizeMode(currentMode: Int): Int {
        return when (currentMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun resizeModeLabel(mode: Int, context: Context): String {
        return when (mode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> context.getString(R.string.player_aspect_fit)
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> context.getString(R.string.player_aspect_stretch)
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> context.getString(R.string.player_aspect_fit_width)
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> context.getString(R.string.player_aspect_fit_height)
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> context.getString(R.string.player_aspect_crop)
            else -> context.getString(R.string.player_aspect_fit)
        }
    }
}
