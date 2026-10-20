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
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.download.Downloads
import java.util.Locale

class WTubeApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        YouTube.init(Locale.getDefault().country)
        Settings.init(this)
        History.init(this)
        SearchHistory.init(this)
        Hidden.init(this)
        Downloads.init(this)
        Account.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .crossfade(true)
            .build()
}
