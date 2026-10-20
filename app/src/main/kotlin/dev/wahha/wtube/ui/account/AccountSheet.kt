package dev.wahha.wtube.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wahha.wtube.R
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Identity
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.innertube.InnerTube
import dev.wahha.wtube.ui.components.Avatar
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.errorMessage
import dev.wahha.wtube.ui.icon.AccountCircle
import dev.wahha.wtube.ui.icon.Check
import dev.wahha.wtube.ui.icon.Logout
import dev.wahha.wtube.ui.icon.SwitchAccount
import dev.wahha.wtube.ui.icon.WIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The account in a screen header: the avatar opens the account sheet, signed out it opens sign-in. */
@Composable
fun AccountButton() {
    val nav = LocalNavigator.current
    val session by Account.session.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }
    IconButton(onClick = { if (session == null) nav.openLogin() else sheet = true }, modifier = Modifier.testTag("account")) {
        val current = session
        if (current?.avatar == null) {
            Icon(WIcons.AccountCircle, contentDescription = stringResource(R.string.account), tint = MaterialTheme.colorScheme.onSurface)
        } else {
            Box {
                Avatar(current.avatar, 28.dp)
                if (current.expired) {
                    Box(Modifier.align(Alignment.TopEnd).size(9.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                }
            }
        }
    }
    val current = session
    if (sheet && current != null) AccountSheet(current) { sheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(session: Session, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    // Null: list not asked for; empty while it loads.
    var identities by remember { mutableStateOf<List<Identity>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Avatar(session.avatar, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(session.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    session.handle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (session.expired) {
                ExpiredCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    onDismiss()
                    nav.openLogin()
                }
            }
            SheetRow(WIcons.SwitchAccount, stringResource(R.string.account_switch)) {
                if (loading) return@SheetRow
                loading = true
                error = null
                scope.launch {
                    try {
                        identities = InnerTube.identities(session)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = errorMessage(e)
                    } finally {
                        loading = false
                    }
                }
            }
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            identities?.forEach { identity ->
                val current = identity.authUser == session.authUser && identity.pageId == session.pageId
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !current) {
                        scope.launch {
                            Account.switchTo(identity)
                            onDismiss()
                        }
                    }.padding(start = 56.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(identity.avatar, 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(identity.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        identity.byline?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (current) Icon(WIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            SheetRow(WIcons.Logout, stringResource(R.string.account_sign_out), Modifier.testTag("sign_out")) {
                scope.launch {
                    Account.signOut()
                    clearWebSession()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** "Session expired" with the way back in; shown at the top of feeds that fell back to local ones. */
@Composable
fun ExpiredCard(modifier: Modifier = Modifier, onSignIn: () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.account_expired), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.account_expired_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            FilledTonalButton(onClick = onSignIn) { Text(stringResource(R.string.account_sign_in_again)) }
        }
    }
}

/** [ExpiredCard] when the stored session expired, nothing otherwise. */
@Composable
fun ExpiredBanner(modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val session by Account.session.collectAsStateWithLifecycle()
    if (session?.expired == true) ExpiredCard(modifier, nav.openLogin)
}
