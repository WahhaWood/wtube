package dev.wahha.wtube.dpi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** A local SOCKS5 proxy that can be started with byedpi arguments; the tester runs on fakes of it. */
interface ProxyEngine {
    /** Starts with [args] (strategy only; address and port are the engine's) and returns the port once it answers. */
    suspend fun start(args: List<String>): Int

    suspend fun stop()
}

/**
 * The app's side of one [DpiService] slot. [onDied] fires on the main thread when a started proxy's process
 * goes away by itself (byedpi exited or crashed, or the system killed it).
 */
class DpiProcess(context: Context, private val service: Class<out DpiService>) : ProxyEngine {
    private val context = context.applicationContext
    private var connection: ServiceConnection? = null
    private var binder: IBinder? = null
    private var ready = false
    var onDied: (() -> Unit)? = null

    override suspend fun start(args: List<String>): Int = withContext(Dispatchers.Main) {
        stop()
        val port = withContext(Dispatchers.IO) { freePort() }
        val connected = CompletableDeferred<IBinder>()
        val lost = CompletableDeferred<Unit>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName) = gone()
            override fun onBindingDied(name: ComponentName) = gone()
            override fun onNullBinding(name: ComponentName) = gone()

            private fun gone() {
                lost.complete(Unit)
                if (connection !== this) return
                val wasReady = ready
                connection = null
                binder = null
                ready = false
                runCatching { context.unbindService(this) }
                if (wasReady) onDied?.invoke()
            }
        }
        // A distinct data URI per start makes the system call onBind with this intent, not reuse an old binding.
        val intent = Intent(context, service)
            .setData("wtube-dpi://start/${nextStart++}".toUri())
            .putExtra(DpiService.EXTRA_ARGS, (listOf("-i", "127.0.0.1", "-p", port.toString()) + args).toTypedArray())
        if (!context.bindService(intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
            throw IOException("Could not bind ${service.simpleName}")
        }
        connection = conn
        try {
            withTimeout(START_TIMEOUT_MS) {
                binder = connected.await()
                while (!socksAnswers(port)) {
                    if (lost.isCompleted) throw IOException("byedpi exited at start")
                    delay(50)
                }
            }
            if (lost.isCompleted || connection !== conn) throw IOException("byedpi exited at start")
            ready = true
        } catch (e: Exception) {
            stop()
            throw if (e is IOException) e else IOException("byedpi did not start", e)
        }
        port
    }

    /** Unbinds, which kills the process, and waits (briefly) until it is gone so the slot can be reused. */
    override suspend fun stop() = withContext(Dispatchers.Main) {
        val conn = connection ?: return@withContext
        val remote = binder
        connection = null
        binder = null
        ready = false
        val dead = CompletableDeferred<Unit>()
        val linked = remote != null && try {
            remote.linkToDeath({ dead.complete(Unit) }, 0)
            true
        } catch (_: RemoteException) {
            false
        }
        runCatching { context.unbindService(conn) }
        if (linked) withTimeoutOrNull(STOP_TIMEOUT_MS) { dead.await() }
    }

    companion object {
        private const val START_TIMEOUT_MS = 10_000L
        private const val STOP_TIMEOUT_MS = 2_000L
        private var nextStart = 0L

        private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        /** The SOCKS5 greeting (no auth) gets its answer: the proxy is listening. */
        private suspend fun socksAnswers(port: Int): Boolean = runInterruptible(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    socket.soTimeout = 250
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    val input = socket.getInputStream()
                    input.read() == 5 && input.read() == 0
                }
            } catch (_: IOException) {
                false
            }
        }
    }
}
