package dev.wahha.wtube.data.account

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wahha.wtube.data.innertube.InnerTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A signed-in Google account, as the youtube.com cookies it was signed in with. Never logged. */
@Serializable
data class Session(
    /** The whole Cookie header for youtube.com. */
    val cookie: String,
    /** Which of the Google accounts signed in to this browser session (`authuser`). */
    val authUser: Int = 0,
    /** A brand channel acted as, or null for the account's own. */
    val pageId: String? = null,
    val name: String? = null,
    val handle: String? = null,
    val avatar: String? = null,
    /** Google stopped accepting the cookies: feeds go back to local ones until the user signs in again. */
    val expired: Boolean = false,
)

/** One identity the cookies can act as: the Google accounts in the session and their brand channels. */
data class Identity(
    val name: String,
    val byline: String?,
    val avatar: String?,
    val authUser: Int,
    val pageId: String?,
    val selected: Boolean,
)

/** The session, sealed with [Vault] in its own DataStore and mirrored into [session]. */
object Account {
    private val Context.dataStore by preferencesDataStore("account")
    private val SessionKey = stringPreferencesKey("session")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<Session?>(null)
    private val loadedState = MutableStateFlow(false)
    private var store: DataStore<Preferences>? = null

    val session: StateFlow<Session?> = state.asStateFlow()

    /** False until the stored session is read: a null [session] before that means "unknown". */
    val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

    /** The session when Google still accepts it. */
    val active: Session? get() = state.value?.takeUnless { it.expired }

    fun init(context: Context) {
        val store = context.applicationContext.dataStore.also { store = it }
        scope.launch {
            val sealed = store.data.first()[SessionKey]
            // Only the exception's class: a parse error message would quote the cookies.
            state.value = sealed?.let { runCatching { json.decodeFromString<Session>(Vault.open(it)) } }
                ?.onFailure { Log.w("Account", "Stored session unreadable (${it.javaClass.simpleName}), signed out") }
                ?.getOrNull()
            loadedState.value = true
        }
    }

    /**
     * Signs in with [cookies] taken from the login page or pasted by the user: checks with YouTube that
     * they are a live session and who it is. Throws when they are not.
     */
    suspend fun signIn(cookies: Map<String, String>) {
        if (!Cookies.canSign(cookies)) throw SignInException()
        val candidate = Session(cookie = Cookies.format(cookies))
        val menu = runCatching { InnerTube.whoAmI(candidate) }
        // The account menu is one endpoint; the account switcher answers the same question another way.
        val me = menu.getOrNull()
            ?: runCatching { InnerTube.identities(candidate).firstOrNull { it.selected } }.getOrNull()
            ?: menu.exceptionOrNull()?.let { throw it }
            ?: throw SignInException()
        save(candidate.copy(name = me.name, handle = me.byline, avatar = me.avatar))
    }

    /** Acts as [identity] from now on: another account of the same browser session or a brand channel. */
    suspend fun switchTo(identity: Identity) {
        val current = state.value ?: return
        save(current.copy(authUser = identity.authUser, pageId = identity.pageId, name = identity.name, avatar = identity.avatar, handle = null))
        // The handle comes only from the account menu; fill it in for the new identity.
        InnerTube.whoAmI(state.value ?: return)?.let { me ->
            state.value?.let { save(it.copy(handle = me.byline, avatar = me.avatar ?: it.avatar)) }
        }
    }

    suspend fun signOut() = save(null)

    /** Google answered as if signed out: keep the account shown, ask to sign in again. */
    fun expire() {
        val current = state.value ?: return
        if (current.expired) return
        Log.i("Account", "Session no longer accepted")
        scope.launch { save(current.copy(expired = true)) }
    }

    private suspend fun save(session: Session?) {
        state.value = session
        val store = store ?: return
        store.edit { prefs ->
            if (session == null) prefs.remove(SessionKey) else prefs[SessionKey] = Vault.seal(json.encodeToString(Session.serializer(), session))
        }
    }
}

/** The cookies are not a signed-in session (or YouTube did not accept them). */
class SignInException : Exception("Not a signed-in YouTube session")

/** YouTube no longer accepts the stored session. */
class SessionExpiredException : Exception("YouTube session expired")
