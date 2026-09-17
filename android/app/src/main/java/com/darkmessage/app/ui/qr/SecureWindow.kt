package com.darkmessage.app.ui.qr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Marks the Activity window secure while this composable is in the composition (spec 6.3):
 * screenshots are blocked, the recents thumbnail is blank and screen recording / casting shows
 * nothing. Used by the sender QR route and by the receiver Confirm step.
 *
 * The flag is cleared again on dispose - unless it was already set by an enclosing
 * [SecureWindow], so nesting two of them cannot unlock the window early.
 */
@Composable
fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = if (view.isInEditMode) null else view.context.findActivity()?.window
        val alreadySecure =
            window != null &&
                (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (window != null && !alreadySecure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        onDispose {
            if (window != null && !alreadySecure) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
