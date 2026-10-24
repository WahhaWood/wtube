package dev.wahha.wtube.dpi

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread

/**
 * Runs byedpi as a SOCKS5 proxy on 127.0.0.1 in a process of its own. byedpi keeps global state and runs
 * once per process, so the process lives exactly as long as one binding: binding starts the proxy with the
 * arguments in the intent, unbinding (or the app's death) kills the process, and a proxy that exits kills it
 * too, which the app sees as a dropped binding. Each subclass is its own process slot (see the manifest).
 */
open class DpiService : Service() {
    override fun onBind(intent: Intent): IBinder {
        val args = intent.getStringArrayExtra(EXTRA_ARGS).orEmpty()
        thread(name = "byedpi") {
            val code = runCatching { ByeDpi.run(arrayOf("ciadpi") + args) }
                .onFailure { Log.e(TAG, "byedpi failed to load", it) }
                .getOrDefault(-1)
            Log.w(TAG, "byedpi exited with $code")
            Process.killProcess(Process.myPid())
        }
        return Binder()
    }

    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }

    companion object {
        const val EXTRA_ARGS = "args"
        private const val TAG = "DpiService"
    }
}

/** The proxy the app's traffic goes through. */
class DpiMainService : DpiService()

/** Two more proxies with other strategies that stay up next to the main one: the [Relay] falls back to them. */
class DpiBackup1Service : DpiService()
class DpiBackup2Service : DpiService()

/** Slots for trying strategies side by side while the main proxy keeps serving. */
class DpiProbe1Service : DpiService()
class DpiProbe2Service : DpiService()
class DpiProbe3Service : DpiService()

/** byedpi's `main`, built from app/src/main/cpp; loaded only in the DPI processes. */
internal object ByeDpi {
    init {
        System.loadLibrary("byedpi")
    }

    external fun run(args: Array<String>): Int
}
