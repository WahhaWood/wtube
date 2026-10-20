package dev.wahha.wtube.ui.components

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableSharedFlow

/** Short confirmations and failures of account actions, from view models that have no context to toast with. */
object Notices {
    private class Notice(@param:StringRes val text: Int, val arg: String?)

    private val events = MutableSharedFlow<Notice>(extraBufferCapacity = 8)

    fun show(@StringRes text: Int, arg: String? = null) {
        events.tryEmit(Notice(text, arg))
    }

    fun failed(error: Throwable) = show(errorMessage(error))

    /** Shows the notices while the app is in the composition. */
    @Composable
    fun Host() {
        val context = LocalContext.current
        LaunchedEffect(context) {
            events.collect { Toast.makeText(context, context.getString(it.text, it.arg), Toast.LENGTH_SHORT).show() }
        }
    }
}
