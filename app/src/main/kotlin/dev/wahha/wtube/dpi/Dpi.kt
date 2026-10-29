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
import dev.wahha.wtube.data.YouTube
import okhttp3.EventListener
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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
import java.net.Inet6Address
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * [Http.setRoute] puts in front of YouTube's hosts. Off, nothing runs and nothing is loaded.
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
    private const val FIRST_ROUTE_WAIT_MS = 10_000L
    private const val SLOTS = 3
    private const val RELAY_LOG_EVERY_MS = 60_000L
    const val ACTION_OPEN = "dev.wahha.wtube.action.OPEN_DPI"

    private lateinit var app: Context
    private lateinit var store: DpiStore
    private lateinit var main: DpiProcess
    private lateinit var backups: List<DpiProcess>
    private lateinit var probes: List<DpiProcess>
    // Lazy: Http.client reports to this object in JVM tests too, where there is no main thread.
    private val scope by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
                // A BindException from the relay or a DataStore error must not take the app down with it.
                ErrorLog.note("[dpi] ${e::class.java.simpleName}: ${e.message}")
                mutableState.value = DpiState.EngineFailed
            },
        )
    }
    private val mutableState = MutableStateFlow<DpiState>(DpiState.Off)
    private val network = MutableStateFlow<String?>(null)
    @Volatile private var validated = false
    private val mutablePresets = MutableStateFlow(StrategyList(0, strategies = emptyList()))
    private val requests = MutableStateFlow(0)
    private val probeLock = Mutex()
    // Slot 0 is the main proxy, 1 and 2 the backups: what each runs (null = not running), where, and under which name.
    private val slotArgs = arrayOfNulls<List<String>>(SLOTS)
    private val slotPorts = IntArray(SLOTS)
    private val slotLabels = Array(SLOTS) { "" }
    @Volatile private var live: List<Backend> = emptyList()
    private var relay: Relay? = null
    private var relayLog: Job? = null
    private val dohClient by lazy {
        Http.base.newBuilder().proxy(Proxy.NO_PROXY).eventListener(EventListener.NONE)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    }
    internal val probeResolver get() = resolver
    private val resolver by lazy { Resolver({ dohClient }, note = ::log) }
    private var cancelledOn: String? = null
    private val deaths = Restarts()
    private val failures = ArrayDeque<Long>()
    private var lastRecheck = 0L
    private var recheck: Job? = null
    private val firstRoute = FirstRoute(FIRST_ROUTE_WAIT_MS)
    private val backupDeaths = Array(SLOTS) { Restarts() }

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
        backups = listOf(DpiBackup1Service::class.java, DpiBackup2Service::class.java).mapIndexed { i, service ->
            DpiProcess(app, service).also { it.onDied = { onBackupDied(i + 1) } }
        }
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
            // The defaults say "off": deciding on them would let the first requests go directly.
            Settings.loaded.first { it }
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

    /**
     * Blocks a YouTube request made right after launch until the bypass has its first route (or is off, or has
     * waited [FIRST_ROUTE_WAIT_MS]), so it does not go directly while byedpi is still starting.
     */
    fun awaitFirstRoute() {
        if (::app.isInitialized) firstRoute.await()
    }

    private suspend fun apply(inputs: Inputs) {
        if (!inputs.enabled) {
            recheck?.cancel()
            stopMain()
            setRoute(null)
            mutableState.value = DpiState.Off
            firstRoute.arrived()
            return
        }
        if (inputs.presets.strategies.isEmpty()) return
        parseProxy(inputs.customProxy)?.let { proxy ->
            stopMain()
            setRoute(proxy)
            mutableState.value = DpiState.CustomProxy
            firstRoute.arrived()
            return
        }
        val key = inputs.network
        if (key == null) {
            // No network: keep whatever runs, it is picked again when one comes.
            if (slotArgs[0] == null) mutableState.value = DpiState.Starting
            return
        }
        if (inputs.customArgs.isNotBlank()) {
            Strategies.validate(inputs.customArgs)?.let { runMain(listOf(CUSTOM to it)) }
            firstRoute.arrived()
            return
        }
        when (val saved = inputs.saved) {
            DpiMemory.DIRECT -> {
                stopMain()
                setRoute(null)
                mutableState.value = DpiState.NotNeeded
                firstRoute.arrived()
            }
            null -> {
                // A search takes a minute; requests go directly meanwhile, as they always did.
                firstRoute.arrived()
                if (cancelledOn == key) mutableState.value = DpiState.Cancelled else search(key, inputs.presets.strategies)
            }
            else -> {
                val slots = saved.split(',').distinct().mapNotNull { id ->
                    Strategies.find(inputs.presets.strategies, id)?.let { Strategies.validate(it.args) }?.let { id to it }
                }.take(SLOTS)
                if (slots.firstOrNull()?.first != saved.substringBefore(',')) {
                    store.forget(key)
                } else {
                    runMain(slots)
                    firstRoute.arrived()
                }
            }
        }
    }

    /**
     * Runs the proxies ([slots]: strategy id and arguments, best first; the first is the main one, the others
     * back it up) behind the [Relay], which is what YouTube's hosts are routed to. Only the ones that changed restart.
     */
    private suspend fun runMain(slots: List<Pair<String, List<String>>>) {
        val id = slots.first().first
        if (slotArgs.indices.all { slotArgs[it] == slots.getOrNull(it)?.second } && relay != null) {
            mutableState.value = DpiState.Running(id)
            return
        }
        mutableState.value = DpiState.Starting
        val engines = listOf(main) + backups
        for (i in 0 until SLOTS) {
            val slot = slots.getOrNull(i)
            if (slot?.second == slotArgs[i] && slot != null) continue
            slotArgs[i] = null
            updateLive()
            if (slot == null) {
                engines[i].stop()
                continue
            }
            try {
                slotPorts[i] = engines[i].start(slot.second)
                slotArgs[i] = slot.second
                slotLabels[i] = slot.first
                updateLive()
            } catch (e: IOException) {
                log("byedpi ${slot.first} did not start: ${e.message}")
                if (i == 0) {
                    setRoute(null)
                    noteDeath()
                    return
                }
            }
        }
        val running = relay ?: withContext(Dispatchers.IO) { Relay(resolver, { live }, ::log) }.also {
            relay = it
            relayLog = scope.launch {
                while (true) {
                    delay(RELAY_LOG_EVERY_MS)
                    relay?.summary()?.let { text -> log("Relay: $text") }
                }
            }
        }
        setRoute(running.proxy)
        mutableState.value = DpiState.Running(id)
        log("Bypass running: ${live.joinToString(" + ") { it.label }} behind the relay on port ${running.port}")
    }

    private fun updateLive() {
        live = (0 until SLOTS).filter { slotArgs[it] != null }.map { Backend(slotPorts[it], slotLabels[it]) }
    }

    private suspend fun stopMain() {
        if (slotArgs.all { it == null } && relay == null) return
        setRoute(null)
        relayLog?.cancel()
        relay?.close()
        relay = null
        val engines = listOf(main) + backups
        for (i in 0 until SLOTS) {
            if (slotArgs[i] == null) continue
            slotArgs[i] = null
            engines[i].stop()
        }
        updateLive()
    }

    /** The main proxy's process went away on its own: direct until it is back, a few restarts, then give up. */
    private fun onMainDied() {
        log("byedpi process died")
        slotArgs[0] = null
        updateLive()
        setRoute(null)
        noteDeath()
    }

    /** A backup's process went away: the relay goes on without it, and it is started again soon. */
    private fun onBackupDied(slot: Int) {
        log("byedpi backup ${slotLabels[slot]} died")
        slotArgs[slot] = null
        updateLive()
        val recent = backupDeaths[slot.coerceIn(backupDeaths.indices)]
        if (!recent.restartAfterDeath(System.currentTimeMillis())) {
            // Left empty until the network or the presets change and the slots are decided afresh.
            log("byedpi backup ${slotLabels[slot]} died 3 times in a minute, not restarting")
            recent.clear()
            return
        }
        scope.launch {
            delay(RESTART_DELAY_MS)
            requests.value++
        }
    }

    private fun noteDeath() {
        if (!deaths.restartAfterDeath(System.currentTimeMillis())) {
            mutableState.value = DpiState.EngineFailed
            return
        }
        scope.launch {
            delay(RESTART_DELAY_MS)
            requests.value++
        }
    }

    /**
     * [stream]: the googlevideo link to check video with instead of the test video's; [first]: ids to try
     * before the recent ones. [keepOnNone]: when nothing works, leave the current route as it is.
     * [v4Twins]: each of those first ones is also tried over IPv4 only, right after itself.
     */
    private suspend fun search(
        key: String,
        presets: List<Strategy>,
        stream: String? = null,
        first: List<String> = emptyList(),
        keepOnNone: Boolean = false,
        v4Twins: Boolean = false,
    ) {
        val ids = first + memory.value.recent
        val preferred = ids.mapNotNull { id -> Strategies.find(presets, id) }
            .flatMap { if (v4Twins && !it.args.startsWith("-X ")) listOf(it, Strategies.v4(it)) else listOf(it) }
        val candidates = (preferred + presets).distinctBy { it.id }
        // Not itself: a stalled video's search runs as the re-check.
        recheck?.takeIf { it !== currentCoroutineContext()[Job] }?.cancel()
        val results = mutableMapOf<String, ProbeResult>()
        mutableState.value = DpiState.Testing(0, candidates.size)
        log("Search in $key: ${candidates.size} candidates")
        val outcome = probeLock.withLock {
            try {
                withContext(Dispatchers.IO) { ProbeRelays(probes.size) }.use { relays ->
                StrategySearch(probes, loggedProbe(stream), relays::proxyOf, ::engineFailed).run(
                    candidates,
                    onProgress = { done, total -> mutableState.value = DpiState.Testing(done, total) },
                    onResult = { strategy, result ->
                        results[strategy.id] = result
                        log("  ${strategy.id}: ${result.summary()}")
                    },
                )
                }
            } finally {
                withContext(NonCancellable) { if (results.isNotEmpty()) store.saveResults(results) }
            }
        }
        log("Search in this network: ${outcome.summary()}")
        when (outcome) {
            SearchOutcome.Direct -> store.remember(key, DpiMemory.DIRECT)
            is SearchOutcome.Found -> store.remember(key, outcome.choice)
            SearchOutcome.None -> if (!keepOnNone) {
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
        val proxy = Http.route
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
        val old = Http.setRoute(proxy) ?: return
        // YouTube calls still on the old route stop (on a blocked network a direct one stalls rather than fails,
        // and the player retries on the new route); ones held at the start move over by themselves (Http.RouteGate).
        // Its idle connections close. Both talk TLS, so not on the main thread.
        scope.launch(Dispatchers.IO) {
            for (call in old.dispatcher.runningCalls()) {
                if (isBypassHost(call.request().url.host) && !Http.isHeld(call)) call.cancel()
            }
            old.connectionPool.evictAll()
        }
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

    private val connectedVia = mutableSetOf<String>()
    private val failedVia = mutableSetOf<String>()

    /** "<domain> via <route>"; googlevideo by server, since a bypass can get through to one and not another. */
    private fun via(host: String, proxy: Proxy): String? {
        val domain = bypassDomain(host) ?: return null
        val name = if (domain == "googlevideo.com") host.lowercase() else domain
        return "$name via ${if (proxy.type() == Proxy.Type.DIRECT) "direct" else proxy}"
    }

    /** From [Http.client]'s event listener: which way connections to YouTube really go, once per domain and route. */
    fun onConnected(host: String, proxy: Proxy) {
        if (!::app.isInitialized || !Settings.current.value.dpiBypass) return
        val via = via(host, proxy) ?: return
        if (synchronized(connectedVia) { connectedVia.add(via) }) scope.launch(Dispatchers.IO) { log("Connected: $via${dnsNote(host)}") }
    }

    /** From [Http.client]'s event listener: a connection to YouTube failed (TCP, SOCKS or TLS), once per server, route and error. */
    fun onConnectFailed(host: String, proxy: Proxy, error: IOException) {
        if (!::app.isInitialized || !Settings.current.value.dpiBypass) return
        val via = via(host, proxy) ?: return
        val line = "$via: ${error.javaClass.simpleName}"
        if (synchronized(failedVia) { failedVia.add(line) }) {
            scope.launch(Dispatchers.IO) { log("Could not connect: $line ${error.message.orEmpty().take(100)}${dnsNote(host)}") }
        }
    }

    /**
     * " (dns v4,v6)" for a googlevideo [host]: the address families the [Relay] got for it, in its order.
     */
    private fun dnsNote(host: String): String {
        if (bypassDomain(host) != "googlevideo.com") return ""
        val families = runCatching { resolver.resolve(host).map { if (it is Inet6Address) "v6" else "v4" }.distinct() }.getOrNull()?.takeIf { it.isNotEmpty() }
        return " (dns ${families?.joinToString(",") ?: "failed"})"
    }

    private val stallChecked = mutableSetOf<String>()

    /**
     * The player has been waiting for video [id] for a while. The search's test video says little about the
     * servers this one comes from (0.20.2: the test video loaded at 10 Mbit/s through bb24, the friend's video
     * never connected), so the strategies are checked again with this video's own stream: the current one and
     * those that worked first, then the rest. Once per video and route; nothing working leaves the route alone.
     */
    fun onVideoStalled(id: String) {
        if (!::app.isInitialized) return
        scope.launch {
            val current = mutableState.value
            if (current != DpiState.NotNeeded && (current !is DpiState.Running || current.strategy == CUSTOM)) return@launch
            val key = network.value ?: return@launch
            if (!stallChecked.add("$id ${Http.route}")) return@launch
            val stream = YouTube.cachedStreamInfo(id)?.videoOnlyStreams?.filter { it.isUrl }?.maxByOrNull { it.bitrate }?.content
                ?: return@launch
            recheck?.cancel()
            recheck = launch {
                val memory = memory.value
                val saved = memory.networks[key]
                log("Video $id stalls via ${Http.route ?: "direct"}: checking with its own stream")
                search(
                    key, mutablePresets.value.strategies, stream,
                    first = saved.orEmpty().split(',').filter { it.isNotBlank() } + memory.results.filterValues { it.works }.keys,
                    keepOnNone = true,
                    v4Twins = true,
                )
                // An unchanged choice leaves the state at Testing: deciding again puts it back.
                requests.value++
            }
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
        val candidates = (saved.orEmpty().split(',').filter { it.isNotBlank() } + memory.value.recent).distinct().mapNotNull { id -> Strategies.find(presets, id) }
        log("YouTube keeps failing, re-checking ${candidates.size} recent strategies")
        val outcome = probeLock.withLock {
            withContext(Dispatchers.IO) { ProbeRelays(probes.size) }.use { relays ->
                StrategySearch(probes, loggedProbe(), relays::proxyOf, ::engineFailed).run(candidates, onResult = { s, r -> log("  ${s.id}: ${r.summary()}") })
            }
        }
        log("Re-check: ${outcome.summary()}")
        when (outcome) {
            SearchOutcome.Direct -> if (saved != DpiMemory.DIRECT) store.remember(key, DpiMemory.DIRECT)
            is SearchOutcome.Found -> if (outcome.choice != saved) store.remember(key, outcome.choice)
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

    private fun engineFailed(strategy: Strategy, why: String) = log("  ${strategy.id}: engine did not start ($why), not tried")

    /** A fresh [Probe] (one stream link per search, or [stream]) that logs the direct check. */
    private fun loggedProbe(stream: String? = null): suspend (Proxy?) -> ProbeResult {
        val probe = if (stream == null) Probe() else Probe(streamUrl = { stream })
        return { proxy -> probe.check(proxy).also { if (proxy == null) log("  direct: ${it.summary()}") } }
    }

    /** To logcat and to the app's own event file (some phones show an app hardly any of its logcat). */
    private fun log(text: String) {
        Log.i(TAG, text)
        ErrorLog.note("[bypass] $text")
    }

    /** Checks the route YouTube takes now (bypass, own proxy or direct), for "Проверить сейчас". */
    suspend fun checkNow(): ProbeResult {
        val proxy = Http.route
        val result = probeLock.withLock { Probe().checkRouted() }
        log("Check now through ${proxy ?: "direct"}: ${result.summary()}")
        return result
    }

    /** One line for the shared log. */
    fun describe(): String {
        if (!::app.isInitialized) return "off"
        val presets = mutablePresets.value
        return "${mutableState.value}, presets v${presets.version} (${presets.strategies.size}), route ${Http.route ?: "direct"}"
    }

    /** The proxy for the sign-in WebView, as a Chromium proxy rule; null = direct. */
    fun webViewProxy(): String? {
        val proxy = Http.route ?: return null
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
    return "site${mark(site)} img${mark(images)} video${mark(video)}" + (if (video) " $kbps kbit/s" else "") +
        (spare?.let { " spare${mark(it)}" } ?: "") + (deep?.let { " deep${mark(it)}" } ?: "") +
        (other?.let { " other${mark(it)}" } ?: "") + (if (note.isNotEmpty()) " ($note)" else "")
}

private fun SearchOutcome.summary(): String = when (this) {
    SearchOutcome.Direct -> "direct"
    is SearchOutcome.Found -> "${strategy.id} (${if (fast) "fast" else "best of the slow"}: ${result.summary()})"
    SearchOutcome.None -> "nothing works"
}

/**
 * One [Relay] per probe engine, each sending to the engine that currently runs there, so that a strategy is
 * judged the way it is used: with the relay's own name lookup and a second address when the first fails.
 */
private class ProbeRelays(count: Int) : java.io.Closeable {
    private val ports = IntArray(count)
    private val relays = List(count) { i ->
        Relay(
            Dpi.probeResolver, { listOf(Backend(ports[i], "probe")) },
            attemptMs = 3_500, deadlineMs = 9_000, maxAttempts = 2,
        )
    }

    fun proxyOf(engine: Int, port: Int): Proxy {
        ports[engine] = port
        return relays[engine].proxy
    }

    override fun close() = relays.forEach { it.close() }
}

/** Deaths of one byedpi process: restart it after each, unless it died [LIMIT] times within [WINDOW_MS]. */
internal class Restarts {
    private val recent = ArrayDeque<Long>()

    /** Notes a death at [now]; false means give up. */
    fun restartAfterDeath(now: Long): Boolean {
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) recent.removeFirst()
        return recent.size < LIMIT
    }

    fun clear() = recent.clear()

    private companion object {
        const val LIMIT = 3
        const val WINDOW_MS = 60_000L
    }
}

/**
 * Holds requests made right after launch until the bypass has its first route, at most [waitMs]. After one
 * timeout the wait is over for good: the route may never come (no network), and every request would
 * otherwise wait the full time again.
 */
internal class FirstRoute(private val waitMs: Long) {
    private val latch = CountDownLatch(1)
    @Volatile private var gaveUp = false

    fun arrived() = latch.countDown()

    fun await() {
        if (gaveUp) return
        if (!latch.await(waitMs, TimeUnit.MILLISECONDS)) gaveUp = true
    }
}
