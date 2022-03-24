import android.view.View
import android.view.Window

object StatusBarUtils {

    // TODO: remove deprecated API
    fun makeStatusBarTransparent(window: Window) {
        val appended = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        // do not overwrite existing value
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or appended
    }
}
