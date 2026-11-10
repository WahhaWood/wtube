package dev.wahha.wtube

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.wahha.wtube.data.AppLanguage
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.ErrorLog
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.Http
import dev.wahha.wtube.data.SearchHistory
import dev.wahha.wtube.data.WatchStats
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.SeenVideos
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.download.Downloads
import dev.wahha.wtube.dpi.Dpi
import dev.wahha.wtube.player.PlayQueue
import dev.wahha.wtube.player.SleepTimer
import dev.wahha.wtube.data.FeedCache
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.res.Configuration
import android.os.Build
import java.io.File

class WTubeApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // The byedpi processes (dpi/DpiService) run the native proxy and nothing of the app.
        if (processName().contains(":dpi")) return
        ErrorLog.init(this)
        AppLanguage.init(this)
        YouTube.init(AppLanguage.systemLocale.country)
        Settings.init(this)
        History.init(this)
        SearchHistory.init(this)
        WatchStats.init(this)
        Hidden.init(this)
        SeenVideos.init(this)
        val restore = !closedByHand()
        PlayQueue.init(this, restore)
        SleepTimer.init(this, restore)
        Downloads.init(this)
        Account.init(this)
        FeedCache.init(this)
        Dpi.init(this)
    }

    /**
     * Whether the user closed the app last time: swiped it from recents or force-stopped it, not the system
     * killing it. PlaybackService.onTaskRemoved hears of a swipe only while it runs as a started service.
     */
    private fun closedByHand(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val last = runCatching { getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 0) }
            .getOrNull()?.firstOrNull { it.processName == packageName } ?: return false
        // Up to some Android 14 a swipe comes as REASON_OTHER, known only by this description.
        return last.reason == ApplicationExitInfo.REASON_USER_REQUESTED || last.description?.contains("remove task") == true
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.forgetSystem()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.calls })) }
            .crossfade(true)
            .build()
}
