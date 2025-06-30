package maulik.barcodescanner.ui

import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * A utility function to configure an Activity's window for a fully immersive,
 * edge-to-edge experience.
 *
 * This function will hide both the status bar and the navigation bar, allowing
 * the app's content to be displayed on the entire screen. It also sets the
 * behavior so that system bars are only revealed with a swipe gesture from the
 * edge of the screen, and they will automatically hide again after a short period.
 */
fun Window.hideSystemUI() {
    // 1. Tell the window to draw behind the system bars
    WindowCompat.setDecorFitsSystemWindows(this, false)

    val controller = WindowInsetsControllerCompat(this, this.decorView)

    // 2. Hide the status and navigation bars
    controller.hide(WindowInsetsCompat.Type.systemBars())

    // 3. Set the behavior for when the user swipes from the edge
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}