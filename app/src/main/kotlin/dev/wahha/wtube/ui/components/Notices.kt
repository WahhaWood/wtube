package dev.wahha.wtube.ui.components

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Short confirmations and failures of account actions, from view models that have no context to toast with. */
object Notices {
    private class Notice(@param:StringRes val text: Int, val arg: String?)

    // Buffered: a notice raised before the activity is up (a file read at start) waits for it.
    private val events = Channel<Notice>(capacity = 8)

    fun show(@StringRes text: Int, arg: String? = null) {
        events.trySend(Notice(text, arg))
    }

    fun failed(error: Throwable) = show(errorMessage(error))

    /** Shows the notices while the app is in the composition. */
    @Composable
    fun Host() {
        val context = LocalContext.current
        LaunchedEffect(context) {
            events.receiveAsFlow().collect { Toast.makeText(context, context.getString(it.text, it.arg), Toast.LENGTH_SHORT).show() }
        }
    }
}
