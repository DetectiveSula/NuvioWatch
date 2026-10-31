package app.nuviowatch.live

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floats a reaction up over whatever is on screen (Nuvio's video), like live-stream
 * reactions. Needs "Display over other apps"; without it the caller falls back to a notification.
 */
object ReactionOverlay {
    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    /** For TVs without a settings screen for it. */
    fun adbCommand(context: Context) = "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"

    /**
     * Without "Display over other apps" (most Fire Sticks have no screen for it), a plain
     * pop-up message still shows over the video: Android lets any app show one, no permission needed.
     */
    fun showMessage(context: Context, emoji: String, from: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(context.applicationContext, "$emoji  $from", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    fun isTv(context: Context) = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    fun show(context: Context, emoji: String, from: String) {
        if (!canShow(context)) return
        Handler(Looper.getMainLooper()).post {
            val wm = context.getSystemService(WindowManager::class.java)
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(10f), dp(20f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(40f).toFloat()
                    setColor(0xCC0A0910.toInt())
                }
                addView(TextView(context).apply { text = emoji; setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f) })
                addView(TextView(context).apply {
                    text = "  $from"
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                })
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = dp(40f)
                y = dp(60f)
            }
            runCatching { wm.addView(bubble, params) }.onFailure { return@post }
            bubble.alpha = 0f
            bubble.translationY = dp(30f).toFloat()
            bubble.animate().alpha(1f).translationY(0f).setDuration(350).withEndAction {
                bubble.animate().translationY(-dp(160f).toFloat()).alpha(0f).setStartDelay(2600).setDuration(900).withEndAction {
                    runCatching { wm.removeView(bubble) }
                }.start()
            }.start()
        }
    }
}
