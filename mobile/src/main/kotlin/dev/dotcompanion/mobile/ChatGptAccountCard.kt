package dev.dotcompanion.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dotcompanion.identity.AccountIdentity
import java.security.MessageDigest
import java.util.Locale

internal fun chatGptAccountLabel(identity: AccountIdentity): String {
    val label = identity.email ?: identity.displayName ?: "ChatGPT account"
    val profile = MessageDigest.getInstance("SHA-256").digest(identity.clientId.toByteArray(Charsets.UTF_8))
        .take(4).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }.uppercase(Locale.ROOT)
    return "$label · Profile $profile"
}

@Composable
internal fun ChatGptAccountCard(
    account: ChatGptAccountUi,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onNewAccount: () -> Unit,
    onSelectAccount: (String) -> Unit,
    chatGptInstalled: Boolean,
    onOpenChatGpt: () -> Unit,
    handoffIssue: String? = null,
) {
    Surface(Modifier.fillMaxWidth().testTag("chatgpt_account_card"), shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ChatGPT account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            account.activeAccount?.let { active ->
                Text(chatGptAccountLabel(active), modifier = Modifier.testTag("chatgpt_account_label"))
            }
            Text(if (account.signedIn) "Identity verified · dot connection still required" else "Sign in to verify your ChatGPT account",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chatgpt_identity_status"))
            when (account.phase) {
                ChatGptSignInPhase.WAITING_FOR_BROWSER, ChatGptSignInPhase.VERIFYING -> {
                    Text(if (account.phase == ChatGptSignInPhase.VERIFYING) "Verifying your account…" else "Complete sign-in in your browser, then return here.")
                    TextButton(onClick = onCancelSignIn, modifier = Modifier.testTag("cancel_chatgpt_sign_in")) { Text("Cancel sign-in") }
                }
                ChatGptSignInPhase.SIGNING_OUT -> Text("Signing out…")
                ChatGptSignInPhase.IDLE -> {
                    if (!account.signedIn) Button(onClick = onSignIn, enabled = account.storageAvailable,
                        modifier = Modifier.fillMaxWidth().testTag("continue_with_chatgpt")) { Text("Continue with ChatGPT") }
                    if (account.signedIn || account.credentialsPresent) OutlinedButton(onClick = onSignOut,
                        enabled = account.storageAvailable, modifier = Modifier.fillMaxWidth().testTag("sign_out_chatgpt")) { Text("Sign out") }
                    if (account.savedAccounts.isNotEmpty() || account.unfinishedRegistration) {
                        account.savedAccounts.filter { it.clientId != account.activeAccount?.clientId }.forEach { saved ->
                            TextButton(onClick = { onSelectAccount(saved.clientId) }) { Text(chatGptAccountLabel(saved)) }
                        }
                        TextButton(onClick = onNewAccount, enabled = account.storageAvailable && account.savedAccounts.size < 10,
                            modifier = Modifier.testTag("add_chatgpt_account")) { Text(if (account.savedAccounts.isEmpty()) "Start a new registration" else "Use another ChatGPT account") }
                    }
                }
            }
            account.issue?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chatgpt_sign_in_issue")) }
            Text("Sign-in identifies your account. It does not give this app your existing conversations, dot memory, or call access.", style = MaterialTheme.typography.bodySmall)
            Text("Call dot in ChatGPT", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text("Open the ChatGPT phone app, choose your dot, and tap its phone button if available. Calling directly from this watch is unavailable.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenChatGpt, enabled = chatGptInstalled,
                modifier = Modifier.fillMaxWidth().testTag("open_chatgpt_app")) { Text("Open ChatGPT") }
            if (!chatGptInstalled) Text("The ChatGPT phone app is not available on this device.", style = MaterialTheme.typography.bodySmall)
            handoffIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
