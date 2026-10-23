package dev.wahha.wtube

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.ErrorLog
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.Http
import dev.wahha.wtube.data.SearchHistory
import dev.wahha.wtube.data.WatchStats
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.download.Downloads
import dev.wahha.wtube.dpi.Dpi
import android.os.Build
import java.io.File
import java.util.Locale

class WTubeApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // The byedpi processes (dpi/DpiService) run the native proxy and nothing of the app.
        if (processName().contains(":dpi")) return
        ErrorLog.init(this)
        YouTube.init(Locale.getDefault().country)
        Settings.init(this)
        History.init(this)
        SearchHistory.init(this)
        WatchStats.init(this)
        Hidden.init(this)
        Downloads.init(this)
        Account.init(this)
        Dpi.init(this)
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.calls })) }
            .crossfade(true)
            .build()
}
