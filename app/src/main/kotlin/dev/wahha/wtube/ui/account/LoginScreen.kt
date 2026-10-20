package dev.wahha.wtube.ui.account

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
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
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Cookies
import dev.wahha.wtube.data.account.SignInException
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
private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&hl=ru" +
    "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Dru%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"

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
            if (pasteMode) CookiePaste(busy, onSubmit = ::signIn) else LoginWebView(onSignedIn = ::signIn)
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
private fun LoginWebView(onSignedIn: (Map<String, String>) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    private var done = false

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
                CookieManager.getInstance().removeAllCookies { loadUrl(LOGIN_URL) }
            }
        },
    )
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
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }
    WebStorage.getInstance().deleteAllData()
}
