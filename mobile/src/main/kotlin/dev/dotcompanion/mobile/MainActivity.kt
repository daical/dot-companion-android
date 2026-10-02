package dev.dotcompanion.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.MessageSource
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
import dev.dotcompanion.core.FailureKind
import dev.dotcompanion.shared.CompanionCoral
import dev.dotcompanion.shared.CompanionNight
import dev.dotcompanion.shared.CompanionViolet
import dev.dotcompanion.shared.CompanionVisual
import dev.dotcompanion.shared.displayLabel

class MainActivity : ComponentActivity() {
    private val repository get() = (application as CompanionApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ui by repository.ui.collectAsStateWithLifecycle()
            PhoneScreen(ui, repository::send, repository::selectMode, repository::connect,
                repository::retry, repository::deleteHistory, showDebugControls = BuildConfig.DEBUG)
        }
    }
    override fun onStart() { super.onStart(); repository.startActive() }
    override fun onStop() { repository.stopActive(); super.onStop() }
}

@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = CompanionCoral, onPrimary = CompanionNight, secondary = CompanionViolet,
        background = CompanionNight, surface = androidx.compose.ui.graphics.Color(0xFF1B1E2B),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF26283C),
    ), content = content)
}

@Composable
fun PhoneScreen(
    ui: PhoneUiState,
    onSend: (String) -> Unit,
    onMode: (ConnectionMode) -> Unit,
    onConnect: (String, String) -> Unit,
    onRetry: (String) -> Unit,
    onDeleteHistory: () -> Unit,
    showDebugControls: Boolean,
    animateCompanion: Boolean = true,
) = PhoneTheme {
    val state = ui.saved.state
    var settings by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var submittedDraft by remember { mutableStateOf<String?>(null) }
    var previousIds by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages, ui.issue) {
        val pending = submittedDraft
        if (pending != null && state.messages.any { it.requestId !in previousIds && it.text == pending.trim() }) {
            if (draft == pending) draft = ""
            submittedDraft = null
        } else if (ui.issue != null) submittedDraft = null
    }
    Surface(modifier = Modifier.fillMaxSize().testTag("phone_screen"), color = CompanionNight) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dot Companion", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Unofficial community project", style = MaterialTheme.typography.labelMedium, color = CompanionViolet)
                }
                TextButton(onClick = { settings = true }, modifier = Modifier.testTag("connection_settings")) { Text("Connection") }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("connection_status")) {
                Column(Modifier.padding(12.dp)) {
                    Text(state.label, style = MaterialTheme.typography.labelLarge)
                    Text("Live dot connection: unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ui.issue?.let { Text(it, Modifier.padding(top = 10.dp).testTag("phone_error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("phone_messages"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "intro") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CompanionVisual(Modifier.size(if (state.messages.isEmpty()) 156.dp else 84.dp), state.messages.lastOrNull()?.status, animateCompanion)
                        if (state.messages.isEmpty()) {
                            Text("A little presence. A clear connection.", style = MaterialTheme.typography.titleMedium)
                            Text("Explore an original interface with local synthetic replies. A real dot subscription has not been connected.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        }
                        if (state.mode == ConnectionMode.LOCAL_PREVIEW) {
                            OutlinedButton(onClick = { onSend("Show me this local preview.") }, modifier = Modifier.padding(top = 14.dp).testTag("synthetic_fixture"),
                                enabled = state.messages.size < CompanionQueue.MAX_MESSAGES) { Text("Try a synthetic fixture") }
                        }
                    }
                }
                items(state.messages, key = { it.requestId }) { message ->
                    MessageCard(message, message.requestId in ui.saved.syntheticRequestIds, onRetry)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            Text("Voice / push to talk is unavailable in this milestone.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp).testTag("voice_unavailable"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Message") }, maxLines = 4,
                    modifier = Modifier.weight(1f).testTag("message_input"), shape = RoundedCornerShape(18.dp),
                    supportingText = { if (draft.toByteArray(Charsets.UTF_8).size > CompanionQueue.MAX_TEXT_BYTES) Text("Message exceeds the 8192-byte limit") })
                Button(onClick = {
                    previousIds = state.messages.map { it.requestId }.toSet()
                    submittedDraft = draft
                    onSend(draft)
                }, enabled = draft.isNotBlank() && draft.toByteArray(Charsets.UTF_8).size <= CompanionQueue.MAX_TEXT_BYTES &&
                    state.messages.size < CompanionQueue.MAX_MESSAGES && submittedDraft == null,
                    modifier = Modifier.height(56.dp).testTag("send_message")) { Text("Send") }
            }
            Text("History stays in app storage. Manage it in Connection.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        }
    }
    if (settings) ConnectionDialog(ui, showDebugControls, onMode, onConnect, onDeleteHistory, onDismiss = { settings = false })
}

@Composable
private fun MessageCard(message: CompanionMessage, syntheticRequest: Boolean, onRetry: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("message_${message.requestId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.align(Alignment.End).widthIn(max = 520.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(14.dp)) {
                Text(if (message.source == MessageSource.WATCH) "You · watch" else "You", style = MaterialTheme.typography.labelSmall, color = CompanionViolet)
                Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                Text((if (syntheticRequest) "Synthetic preview · " else "") +
                    (if (message.failureKind == FailureKind.REMOTE) "Bridge delivery failed · recover the subscription, then send a new request" else message.status.displayLabel()), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                if (message.status == MessageStatus.FAILED && message.failureKind == FailureKind.TRANSPORT) TextButton(onClick = { onRetry(message.requestId) }) { Text("Retry") }
            }
        }
        message.reply?.let { reply ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, CompanionViolet.copy(alpha = .28f))) {
                Column(Modifier.padding(14.dp)) {
                    Text(when (message.replyProvenance) {
                        ReplyProvenance.LOCAL_SYNTHETIC -> "Synthetic fixture · generated on device"
                        ReplyProvenance.LOCAL_BRIDGE -> "Local bridge reply · dot unverified"
                        null -> "Unverified reply"
                    }, style = MaterialTheme.typography.labelMedium, color = CompanionCoral)
                    Text(reply, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ConnectionDialog(ui: PhoneUiState, debug: Boolean, onMode: (ConnectionMode) -> Unit,
    onConnect: (String, String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var endpoint by remember { mutableStateOf("http://10.0.2.2:8787") }
    // Deliberately not rememberSaveable, preferences or any persistent state.
    var token by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connection") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live dot connection: unavailable", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("settings_live_status"))
            Text("This app cannot access existing ChatGPT conversations or native dot calls. Local bridge replies remain unverified.")
            OutlinedButton(onClick = { onMode(ConnectionMode.LOCAL_PREVIEW) }, modifier = Modifier.fillMaxWidth().testTag("mode_preview")) { Text("Use local synthetic preview") }
            OutlinedButton(onClick = { onMode(ConnectionMode.DISCONNECTED) }, modifier = Modifier.fillMaxWidth().testTag("mode_disconnected")) { Text("Disconnect · keep messages") }
            if (debug) {
                Text("Debug local bridge", style = MaterialTheme.typography.titleSmall)
                Text("Connecting sends queued non-preview messages to this endpoint. Sync runs while the phone app is open. The development token stays in memory and is cleared on disconnect or process exit.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("Development token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onConnect(endpoint, token); token = "" }, enabled = token.isNotBlank() && !ui.connecting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ui.connecting) "Checking local bridge…" else "Connect local bridge")
                }
            }
            Text("Voice and push to talk: unavailable", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete_history")) { Text("Delete local history") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete local history?") },
        text = { Text("This removes queued messages and replies from this phone. It does not remove the watch's queue or data already sent to a bridge.") },
        confirmButton = { TextButton(onClick = { onDelete(); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep history") } })
}
