package dev.wahha.wtube.ui.account

import dev.wahha.wtube.data.AppLocale
import dev.wahha.wtube.data.ErrorLog
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wahha.wtube.R
import dev.wahha.wtube.dpi.Dpi
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Cookies
import dev.wahha.wtube.data.account.SignInException
import dev.wahha.wtube.data.innertube.hostIn
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.errorDetail
import dev.wahha.wtube.ui.components.errorMessage
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.ContentPaste
import dev.wahha.wtube.ui.icon.UploadFile
import dev.wahha.wtube.ui.icon.WIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// As SimpMusic signs in: Google's own sign-in page, continuing to YouTube once done.
// Google's pages speak the interface language.
private fun loginUrl(language: String = AppLocale.language): String =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&hl=$language" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3D$language" +
        "%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"

/**
 * Google's sign-in page in a WebView. Once it lands back on YouTube the WebView holds the session's
 * cookies: they are taken, checked with YouTube and stored, and the WebView's own copy is wiped. If
 * Google refuses an embedded browser, cookies exported from a desktop browser can be pasted instead.
 */
@Composable
fun LoginScreen() {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var pasteMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.login_failed)
    val badCookies = stringResource(R.string.login_bad_cookies)
    val context = LocalContext.current

    fun signIn(cookies: Map<String, String>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                Account.signIn(cookies)
                clearWebSession()
                nav.back()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInException) {
                error = if (Cookies.canSign(cookies)) failed else badCookies
            } catch (e: Exception) {
                val reason = errorDetail(e)
                Log.w("Login", "Sign-in check failed: $reason")
                ErrorLog.note("[login] sign-in check failed: $reason")
                error = context.getString(errorMessage(e)) + "\n" + reason
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.back) { Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { pasteMode = !pasteMode }) {
                Icon(WIcons.ContentPaste, contentDescription = stringResource(R.string.login_cookies))
            }
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (pasteMode) CookiePaste(busy, onSubmit = ::signIn) else LoginWebView(onSignedIn = ::signIn, onGone = nav.back)
            if (busy) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.login_signing_in), modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(onSignedIn: (Map<String, String>) -> Unit, onGone: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.saveFormData = false
                webView = this
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    private var done = false

                    // Only Google's sign-in and YouTube load here; anything else a page links to goes to the browser.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val url = request.url
                        if (url.scheme == "https" && isSignInHost(url.host)) return false
                        if (url.scheme == "http" || url.scheme == "https") {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                        }
                        return true
                    }

                    // A killed or crashed renderer would take the whole app down unless handled: leave the screen,
                    // whose disposal destroys this WebView, and sign-in can simply be started again.
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        ErrorLog.note("[login] web page renderer gone, crashed=${detail.didCrash()}")
                        onGone()
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (done || !isYouTubeHome(url)) return
                        val cookies = Cookies.parse(CookieManager.getInstance().getCookie(Cookies.ORIGIN).orEmpty())
                        // Google sets the signing cookie on its way back; a page without it is not signed in yet.
                        if (Cookies.canSign(cookies)) {
                            done = true
                            onSignedIn(cookies)
                        }
                    }
                }
                // Start from a clean browser: no half-finished sign-in or other account left from before.
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies {
                    // Through the blocking bypass when it runs: the WebView does not use the app's OkHttp.
                    val proxy = if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) Dpi.webViewProxy() else null
                    if (proxy != null) {
                        val config = ProxyConfig.Builder().addProxyRule(proxy).build()
                        ProxyController.getInstance().setProxyOverride(config, ContextCompat.getMainExecutor(context)) { loadUrl(loginUrl()) }
                    } else {
                        loadUrl(loginUrl())
                    }
                }
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            Dpi.closeWebViewProxy()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride({}, {})
            }
            // Leaving without a sign-in (back, an error) must not leave Google's cookies in the browser profile.
            clearWebSession()
            webView?.apply {
                // Pages and the back list of Google's sign-in stay out of the app's files too.
                clearCache(true)
                clearHistory()
                destroy()
            }
            webView = null
        }
    }
}

private val LoginDomains = listOf("google.com", "youtube.com", "gstatic.com")

/**
 * Where Google's sign-in may take the page. Besides [LoginDomains], Google sets the session on the country's own
 * domain too (`accounts.google.com.ua/accounts/SetSID` for a Ukrainian SIM): `google.<country>`, `google.com.<country>`
 * and `google.co.<country>`. Sending that step to the browser left the sign-in half done.
 */
internal fun isSignInHost(host: String?): Boolean {
    if (hostIn(host, LoginDomains)) return true
    val labels = host?.trimEnd('.')?.lowercase()?.split('.') ?: return false
    val google = labels.lastIndexOf("google").takeIf { it >= 0 } ?: return false
    val suffix = labels.drop(google + 1)
    val country = suffix.lastOrNull()?.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: return false
    return suffix.size == 1 || suffix.size == 2 && suffix[0] in setOf("com", "co")
}

private fun isYouTubeHome(url: String): Boolean {
    val uri = Uri.parse(url)
    return uri.host in setOf("www.youtube.com", "m.youtube.com", "youtube.com") && uri.path?.startsWith("/signin") != true
}

@Composable
private fun CookiePaste(busy: Boolean, onSubmit: (Map<String, String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { text = it.readText() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.login_cookies_help), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.login_cookies_hint)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { pickFile.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = !busy) {
                Icon(WIcons.UploadFile, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.login_cookies_file))
            }
            Button(onClick = { onSubmit(Cookies.fromUserInput(text)) }, enabled = !busy && text.isNotBlank()) {
                Text(stringResource(R.string.account_sign_in))
            }
        }
    }
}

/** Forgets everything the sign-in page stored, so the session lives only in the encrypted store. */
fun clearWebSession() {
    // flush() only after the removal is done, or the old cookies are written to disk.
    CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
    WebStorage.getInstance().deleteAllData()
}
