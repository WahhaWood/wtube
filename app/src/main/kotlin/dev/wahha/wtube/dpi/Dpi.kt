package dev.wahha.wtube.dpi

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.wahha.wtube.MainActivity
import dev.wahha.wtube.R
import dev.wahha.wtube.data.ErrorLog
import dev.wahha.wtube.data.Http
import dev.wahha.wtube.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest

/** What the bypass is doing, for Settings and the log. */
sealed interface DpiState {
    data object Off : DpiState

    /** Starting the proxy, or waiting for a network. */
    data object Starting : DpiState

    data class Testing(val done: Int, val total: Int) : DpiState

    /** YouTube goes through byedpi with [strategy] (a preset id or [Dpi.CUSTOM]). */
    data class Running(val strategy: String) : DpiState

    data object NotNeeded : DpiState
    data object CustomProxy : DpiState
    data object NoStrategy : DpiState
    data object EngineFailed : DpiState

    /** The user stopped a search; nothing is tried in this network until they ask again. */
    data object Cancelled : DpiState
}

/**
 * The YouTube blocking bypass: byedpi as a local SOCKS5 proxy in a process of its own ([DpiService]), which
 * [BypassSelector] puts in front of YouTube's hosts in [Http.client]. Off, nothing runs and nothing is loaded.
 * On, the strategy is picked per network: one remembered for it, else a search through the presets
 * ([StrategySearch]); a run of failed YouTube requests triggers a quick re-check of the recent strategies.
 */
@SuppressLint("StaticFieldLeak") // Holds the application context only.
object Dpi {
    const val CUSTOM = "custom"
    private const val TAG = "Dpi"
    private const val CHANNEL = "dpi"
    private const val NOTIFICATION_ID = 7
    private const val FAILURES_TO_RECHECK = 3
    private const val FAILURE_WINDOW_MS = 60_000L
    private const val RECHECK_EVERY_MS = 5 * 60_000L
    private const val RESTART_DELAY_MS = 5_000L
    const val ACTION_OPEN = "dev.wahha.wtube.action.OPEN_DPI"

    private lateinit var app: Context
    private lateinit var store: DpiStore
    private lateinit var main: DpiProcess
    private lateinit var probes: List<DpiProcess>
    // Lazy: Http.client reports to this object in JVM tests too, where there is no main thread.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private val mutableState = MutableStateFlow<DpiState>(DpiState.Off)
    private val network = MutableStateFlow<String?>(null)
    @Volatile private var validated = false
    private val mutablePresets = MutableStateFlow(StrategyList(0, strategies = emptyList()))
    private val requests = MutableStateFlow(0)
    private val probeLock = Mutex()
    private var mainArgs: List<String>? = null
    private var cancelledOn: String? = null
    private val deaths = ArrayDeque<Long>()
    private val failures = ArrayDeque<Long>()
    private var lastRecheck = 0L
    private var recheck: Job? = null

    val state: StateFlow<DpiState> = mutableState.asStateFlow()
    val presets: StateFlow<StrategyList> = mutablePresets.asStateFlow()
    lateinit var memory: StateFlow<DpiMemory>
        private set

    /** The current network's key, for the manual list's mark. */
    val networkKey: StateFlow<String?> = network.asStateFlow()

    private data class Inputs(
        val enabled: Boolean,
        val customArgs: String,
        val customProxy: String,
        val network: String?,
        val saved: String?,
        val presets: StrategyList,
        val request: Int,
    )

    fun init(context: Context) {
        app = context.applicationContext
        store = DpiStore(app)
        memory = store.memory.stateIn(scope, SharingStarted.Eagerly, DpiMemory())
        main = DpiProcess(app, DpiMainService::class.java).also { it.onDied = ::onMainDied }
        probes = listOf(DpiProbe1Service::class.java, DpiProbe2Service::class.java, DpiProbe3Service::class.java).map { DpiProcess(app, it) }
        watchNetwork()
        scope.launch { network.collect { if (Settings.current.value.dpiBypass) log("Network: ${it ?: "none"}") } }
        scope.launch {
            mutableState.map { if (it is DpiState.Testing) "Testing" else it.toString() }.distinctUntilChanged().collect { log("State: $it") }
        }
        scope.launch {
            // Off by default: the presets are read only once the bypass is on.
            Settings.current.first { it.dpiBypass }
            mutablePresets.value = withContext(Dispatchers.IO) { Presets.load(app) }
        }
        scope.launch {
            store.migrate()
            combine(Settings.current, store.memory, network, mutablePresets, requests) { settings, memory, key, presets, request ->
                Inputs(settings.dpiBypass, memory.customArgs, memory.customProxy, key, key?.let { memory.networks[it] }, presets, request)
            }.distinctUntilChanged().collectLatest { apply(it) }
        }
        scope.launch {
            Settings.current.first { it.dpiBypass }
            mutablePresets.first { it.strategies.isNotEmpty() }
            if (System.currentTimeMillis() - store.memory.first().presetsCheckedAt > Presets.EVERY_MS) refreshPresets()
        }
    }

    // ---- Decisions ----

    private suspend fun apply(inputs: Inputs) {
        if (!inputs.enabled) {
            recheck?.cancel()
            stopMain()
            setRoute(null)
            mutableState.value = DpiState.Off
            return
        }
        if (inputs.presets.strategies.isEmpty()) return
        parseProxy(inputs.customProxy)?.let { proxy ->
            stopMain()
            setRoute(proxy)
            mutableState.value = DpiState.CustomProxy
            return
        }
        val key = inputs.network
        if (key == null) {
            // No network: keep whatever runs, it is picked again when one comes.
            if (mainArgs == null) mutableState.value = DpiState.Starting
            return
        }
        if (inputs.customArgs.isNotBlank()) {
            Strategies.validate(inputs.customArgs)?.let { runMain(CUSTOM, it) }
            return
        }
        when (val saved = inputs.saved) {
            DpiMemory.DIRECT -> {
                stopMain()
                setRoute(null)
                mutableState.value = DpiState.NotNeeded
            }
            null -> if (cancelledOn == key) {
                mutableState.value = DpiState.Cancelled
            } else {
                search(key, inputs.presets.strategies)
            }
            else -> {
                val strategy = inputs.presets.strategies.firstOrNull { it.id == saved }
                val args = strategy?.let { Strategies.validate(it.args) }
                if (args == null) store.forget(key) else runMain(saved, args)
            }
        }
    }

    private suspend fun runMain(id: String, args: List<String>) {
        if (mainArgs == args) {
            mutableState.value = DpiState.Running(id)
            return
        }
        mutableState.value = DpiState.Starting
        try {
            val port = main.start(args)
            mainArgs = args
            setRoute(localSocks(port))
            mutableState.value = DpiState.Running(id)
            log("Bypass running: $id on port $port")
        } catch (e: IOException) {
            log("byedpi did not start: ${e.message}")
            mainArgs = null
            setRoute(null)
            noteDeath()
        }
    }

    private suspend fun stopMain() {
        if (mainArgs == null) return
        mainArgs = null
        setRoute(null)
        main.stop()
    }

    /** The proxy's process went away on its own: direct until it is back, a few restarts, then give up. */
    private fun onMainDied() {
        log("byedpi process died")
        mainArgs = null
        setRoute(null)
        noteDeath()
    }

    private fun noteDeath() {
        val now = System.currentTimeMillis()
        deaths.addLast(now)
        while (deaths.isNotEmpty() && now - deaths.first() > 60_000) deaths.removeFirst()
        if (deaths.size >= 3) {
            mutableState.value = DpiState.EngineFailed
            return
        }
        scope.launch {
            delay(RESTART_DELAY_MS)
            requests.value++
        }
    }

    private suspend fun search(key: String, presets: List<Strategy>) {
        val recent = memory.value.recent.mapNotNull { id -> presets.firstOrNull { it.id == id } }
        val candidates = (recent + presets).distinctBy { it.id }
        recheck?.cancel()
        val results = mutableMapOf<String, ProbeResult>()
        mutableState.value = DpiState.Testing(0, candidates.size)
        log("Search in $key: ${candidates.size} candidates")
        val outcome = probeLock.withLock {
            try {
                StrategySearch(probes, loggedProbe()).run(
                    candidates,
                    onProgress = { done, total -> mutableState.value = DpiState.Testing(done, total) },
                    onResult = { strategy, result ->
                        results[strategy.id] = result
                        log("  ${strategy.id}: ${result.summary()}")
                    },
                )
            } finally {
                withContext(NonCancellable) { if (results.isNotEmpty()) store.saveResults(results) }
            }
        }
        log("Search in this network: ${outcome.summary()}")
        when (outcome) {
            SearchOutcome.Direct -> store.remember(key, DpiMemory.DIRECT)
            is SearchOutcome.Found -> store.remember(key, outcome.strategy.id)
            SearchOutcome.None -> {
                stopMain()
                mutableState.value = DpiState.NoStrategy
            }
        }
    }

    // ---- What the user does ----

    /** Forgets this network's choice and searches again. */
    fun retest() {
        val key = network.value ?: return
        cancelledOn = null
        scope.launch {
            store.forget(key)
            requests.value++
        }
    }

    fun cancelSearch() {
        cancelledOn = network.value
        requests.value++
    }

    /** Uses preset [id] in this network from now on. */
    fun choose(id: String) {
        val key = network.value ?: return
        cancelledOn = null
        scope.launch { store.remember(key, id) }
    }

    fun setCustomArgs(args: String) = scope.launch { store.setCustomArgs(args) }

    fun setCustomProxy(proxy: String) = scope.launch { store.setCustomProxy(proxy) }

    suspend fun refreshPresets(): Presets.Refresh {
        val proxy = BypassSelector.proxy
        val (result, etag) = Presets.refresh(app, mutablePresets.value, memory.value.presetsEtag, proxy)
        if (result != Presets.Refresh.Failed) store.presetsChecked(etag, System.currentTimeMillis())
        if (result is Presets.Refresh.Updated) {
            log("Presets updated to version ${result.list.version}")
            mutablePresets.value = result.list
        }
        return result
    }

    // ---- Network ----

    private fun setRoute(proxy: Proxy?) {
        if (BypassSelector.proxy == proxy) return
        BypassSelector.proxy = proxy
        // Pooled connections were made on the old route; idle ones must not carry YouTube past the new one.
        // Closing them talks TLS, so not on the main thread.
        scope.launch(Dispatchers.IO) { Http.client.connectionPool.evictAll() }
    }

    private fun watchNetwork() {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val telephony = app.getSystemService(TelephonyManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var caps: NetworkCapabilities? = null
            private var link: LinkProperties? = null

            override fun onCapabilitiesChanged(net: Network, capabilities: NetworkCapabilities) {
                caps = capabilities
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                update()
            }

            override fun onLinkPropertiesChanged(net: Network, properties: LinkProperties) {
                link = properties
                update()
            }

            override fun onLost(net: Network) {
                caps = null
                link = null
                validated = false
                network.value = null
            }

            private fun update() {
                val capabilities = caps ?: return
                val properties = link ?: return
                val cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                network.value = networkKey(
                    cellular = cellular,
                    operator = if (cellular) runCatching { telephony?.networkOperator }.getOrNull() else null,
                    gateways = properties.routes.mapNotNull { route -> route.gateway?.takeIf { route.isDefaultRoute }?.hostAddress },
                    dns = properties.dnsServers.mapNotNull { it.hostAddress },
                )
            }
        })
    }

    // ---- Watching YouTube fail ----

    /** From [Http.client]'s event listener: a request to [host] failed on the network (not cancelled). */
    fun onRequestFailed(host: String) {
        if (!::app.isInitialized || !isBypassHost(host)) return
        scope.launch {
            val current = mutableState.value
            if (current !is DpiState.Running && current != DpiState.NotNeeded) return@launch
            if (!validated) return@launch
            val now = System.currentTimeMillis()
            failures.addLast(now)
            while (failures.isNotEmpty() && now - failures.first() > FAILURE_WINDOW_MS) failures.removeFirst()
            if (failures.size < FAILURES_TO_RECHECK || now - lastRecheck < RECHECK_EVERY_MS || recheck?.isActive == true) return@launch
            failures.clear()
            lastRecheck = now
            recheck = launch { quickRecheck() }
        }
    }

    fun onRequestSucceeded(host: String) {
        if (::app.isInitialized && isBypassHost(host) && failures.isNotEmpty()) scope.launch { failures.clear() }
    }

    /** YouTube keeps failing in a network that had a working choice: try directly and the recent strategies. */
    private suspend fun quickRecheck() {
        val key = network.value ?: return
        val presets = mutablePresets.value.strategies
        val saved = memory.value.networks[key]
        val candidates = (listOfNotNull(saved) + memory.value.recent).distinct().mapNotNull { id -> presets.firstOrNull { it.id == id } }
        log("YouTube keeps failing, re-checking ${candidates.size} recent strategies")
        val outcome = probeLock.withLock {
            StrategySearch(probes, loggedProbe()).run(candidates, onResult = { s, r -> log("  ${s.id}: ${r.summary()}") })
        }
        log("Re-check: ${outcome.summary()}")
        when (outcome) {
            SearchOutcome.Direct -> if (saved != DpiMemory.DIRECT) store.remember(key, DpiMemory.DIRECT)
            is SearchOutcome.Found -> if (outcome.strategy.id != saved) store.remember(key, outcome.strategy.id)
            SearchOutcome.None -> notifyBroken()
        }
    }

    private fun notifyBroken() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(app)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.dpi_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(app.getString(R.string.dpi_broken_title))
            .setContentText(app.getString(R.string.dpi_broken_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** A fresh [Probe] (one stream link per search) that logs the direct check. */
    private fun loggedProbe(): suspend (Proxy?) -> ProbeResult {
        val probe = Probe()
        return { proxy -> probe.check(proxy).also { if (proxy == null) log("  direct: ${it.summary()}") } }
    }

    /** To logcat and to the app's own event file (some phones show an app hardly any of its logcat). */
    private fun log(text: String) {
        Log.i(TAG, text)
        ErrorLog.note("[bypass] $text")
    }

    /** Checks the route YouTube takes now (bypass, own proxy or direct), for "Проверить сейчас". */
    suspend fun checkNow(): ProbeResult {
        val proxy = BypassSelector.proxy
        val result = probeLock.withLock { Probe().check(proxy) }
        log("Check now through ${proxy ?: "direct"}: ${result.summary()}")
        return result
    }

    /** One line for the shared log. */
    fun describe(): String {
        if (!::app.isInitialized) return "off"
        val presets = mutablePresets.value
        return "${mutableState.value}, presets v${presets.version} (${presets.strategies.size}), route ${BypassSelector.proxy ?: "direct"}"
    }

    /** The proxy for the sign-in WebView, as a Chromium proxy rule; null = direct. */
    fun webViewProxy(): String? {
        val proxy = BypassSelector.proxy ?: return null
        val address = proxy.address() as? java.net.InetSocketAddress ?: return null
        val scheme = if (proxy.type() == Proxy.Type.HTTP) "http" else "socks5"
        return "$scheme://${address.hostString}:${address.port}"
    }
}

/**
 * A stable, non-identifying key for a network: mobile by operator (MCC+MNC), anything else (Wi-Fi,
 * Ethernet, a VPN) by a hash of its gateways and DNS servers.
 */
fun networkKey(cellular: Boolean, operator: String?, gateways: List<String>, dns: List<String>): String {
    if (cellular) return "cell:" + operator?.takeIf { it.isNotBlank() }.orEmpty().ifEmpty { "unknown" }
    val digest = MessageDigest.getInstance("SHA-256").digest((gateways.sorted() + "|" + dns.sorted()).joinToString(",").toByteArray())
    return "net:" + digest.take(6).joinToString("") { "%02x".format(it) }
}

private fun ProbeResult.summary(): String {
    fun mark(ok: Boolean) = if (ok) "+" else "-"
    return "site${mark(site)} img${mark(images)} video${mark(video)}" + (if (video) " $kbps kbit/s" else "") + (if (note.isNotEmpty()) " ($note)" else "")
}

private fun SearchOutcome.summary(): String = when (this) {
    SearchOutcome.Direct -> "direct"
    is SearchOutcome.Found -> "${strategy.id} (${if (fast) "fast" else "best of the slow"}: ${result.summary()})"
    SearchOutcome.None -> "nothing works"
}
