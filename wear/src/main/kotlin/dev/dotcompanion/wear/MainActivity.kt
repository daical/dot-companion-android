package dev.dotcompanion.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.FailureKind
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
import dev.dotcompanion.shared.CompanionCoral
import dev.dotcompanion.shared.CompanionNight
import dev.dotcompanion.shared.CompanionViolet
import dev.dotcompanion.shared.CompanionVisual

class MainActivity : ComponentActivity() {
    private val repository get() = (application as WatchApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ui by repository.ui.collectAsStateWithLifecycle()
            WatchScreen(ui, repository::send, repository::selectMode, repository::retry, repository::deleteHistory)
        }
    }
    override fun onStart() { super.onStart(); repository.startActive() }
    override fun onStop() { repository.stopActive(); super.onStop() }
}

@Composable
fun WatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = Colors(primary = CompanionCoral, onPrimary = CompanionNight, secondary = CompanionViolet,
        background = CompanionNight, onBackground = Color(0xFFF2EFF9), surface = Color(0xFF232536), onSurface = Color(0xFFF2EFF9)), content = content)
}

@Composable
fun WatchScreen(ui: WatchUiState, onSend: (String) -> Unit, onMode: (ConnectionMode) -> Unit,
    onRetry: (String) -> Unit, onDeleteHistory: () -> Unit, animateCompanion: Boolean = true) = WatchTheme {
    var writing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var submittedDraft by remember { mutableStateOf<String?>(null) }
    var previousIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(ui.saved.state.messages, ui.issue) {
        val pending = submittedDraft
        if (pending != null && ui.saved.state.messages.any { it.requestId !in previousIds && it.text == pending.trim() }) {
            if (draft == pending) draft = ""
            writing = false
            submittedDraft = null
        } else if (ui.issue != null) submittedDraft = null
    }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val screenShape = if (LocalConfiguration.current.isScreenRound) Modifier.clip(CircleShape) else Modifier
    Scaffold(modifier = Modifier.fillMaxSize().then(screenShape).background(CompanionNight).testTag("watch_screen"), timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("watch_list"),
            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 28.dp),
            autoCentering = AutoCenteringParams(itemIndex = 0),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (deleting) {
                item { Text("Delete local history?", textAlign = TextAlign.Center, style = MaterialTheme.typography.title3) }
                item { Text("Queued requests and replies on this watch will be removed. Phone and bridge data remain.", textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1) }
                item { Chip(onClick = { onDeleteHistory(); deleting = false }, label = { Text("Delete") }, modifier = Modifier.fillMaxWidth().testTag("watch_confirm_delete")) }
                item { Chip(onClick = { deleting = false }, label = { Text("Keep history") }, modifier = Modifier.fillMaxWidth()) }
            } else if (writing) {
                item { Text(if (ui.saved.state.mode == ConnectionMode.LOCAL_PREVIEW) "Synthetic preview text" else "Write a message", textAlign = TextAlign.Center, style = MaterialTheme.typography.title3) }
                item {
                    BasicTextField(value = draft, onValueChange = { draft = it }, maxLines = 4,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).border(1.dp, CompanionViolet, RoundedCornerShape(12.dp))
                            .padding(12.dp).testTag("watch_message_input").semantics { contentDescription = "Type a message" },
                        decorationBox = { field -> Box { if (draft.isEmpty()) Text("Type here", color = CompanionViolet, style = MaterialTheme.typography.body2); field() } })
                }
                item {
                    Chip(onClick = {
                        previousIds = ui.saved.state.messages.map { it.requestId }.toSet()
                        submittedDraft = draft
                        onSend(draft)
                    }, label = { Text(if (submittedDraft == null) "Send text" else "Saving…") },
                        enabled = draft.isNotBlank() && draft.toByteArray(Charsets.UTF_8).size <= CompanionQueue.MAX_TEXT_BYTES && ui.saved.state.messages.size < CompanionQueue.MAX_MESSAGES && submittedDraft == null,
                        modifier = Modifier.fillMaxWidth().testTag("watch_send_text"))
                }
                item { Text("Voice / push to talk unavailable", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                ui.issue?.let { error -> item { Text(error, style = MaterialTheme.typography.caption1, color = Color(0xFFFFB5B5), textAlign = TextAlign.Center) } }
                item { Chip(onClick = { writing = false }, label = { Text("Back") }, modifier = Modifier.fillMaxWidth()) }
            } else {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        CompanionVisual(Modifier.size(40.dp), ui.saved.state.messages.lastOrNull()?.status, animateCompanion)
                        Text("Dot Companion", fontSize = 13.sp, lineHeight = 16.sp,
                            textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                        Text(ui.connectionLabel, color = CompanionViolet, fontSize = 12.sp, lineHeight = 14.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("watch_connection_status"))
                        Text("Live dot: unavailable", fontSize = 12.sp, lineHeight = 14.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("watch_live_status"))
                    }
                }
                item { Chip(onClick = { writing = true }, label = { Text("Write text") },
                    secondaryLabel = { Text(if (ui.saved.state.mode == ConnectionMode.LOCAL_PREVIEW) "Synthetic replies only" else "Saved before delivery") },
                    modifier = Modifier.fillMaxWidth().testTag("watch_write_text")) }
                if (ui.saved.state.mode == ConnectionMode.LOCAL_PREVIEW) {
                    item { Chip(onClick = { onSend("Show me the synthetic watch preview.") }, label = { Text("Try a fixture") },
                        secondaryLabel = { Text("Synthetic · on this watch") }, enabled = ui.saved.state.messages.size < CompanionQueue.MAX_MESSAGES,
                        modifier = Modifier.fillMaxWidth().testTag("watch_synthetic_fixture")) }
                    item { Chip(onClick = { onMode(ConnectionMode.LOCAL_BRIDGE) }, label = { Text("Use phone link") },
                        secondaryLabel = { Text("Dot connection unverified") }, modifier = Modifier.fillMaxWidth().testTag("watch_phone_link")) }
                }
                ui.issue?.let { error -> item { Text(error, style = MaterialTheme.typography.caption1, color = Color(0xFFFFB5B5), textAlign = TextAlign.Center) } }
                ui.saved.state.messages.reversed().forEach { message ->
                    item(key = message.requestId) {
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colors.surface, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(message.text, style = MaterialTheme.typography.body2)
                            val acknowledged = message.requestId in ui.saved.phoneAcknowledgedIds
                            val synthetic = message.requestId in ui.saved.syntheticRequestIds
                            val delivery = when {
                                message.failureKind == FailureKind.REMOTE -> "Bridge failed. Recover the subscription, then send a new request."
                                message.status == MessageStatus.FAILED && (acknowledged || message.requestId in ui.saved.phoneReportedFailureIds) -> "Phone transport failed · open phone to retry"
                                message.status == MessageStatus.FAILED -> "Watch transport failed · Retry"
                                message.status == MessageStatus.REPLIED -> "Reply received"
                                synthetic -> "Synthetic preview · processing locally"
                                acknowledged -> "Saved on phone · no reply yet"
                                message.status == MessageStatus.AWAITING_REPLY -> "Phone reports waiting · receipt unconfirmed"
                                message.status == MessageStatus.SENDING -> "Sending to phone · receipt pending"
                                else -> "Queued on watch"
                            }
                            Text(delivery, style = MaterialTheme.typography.caption2, color = CompanionViolet,
                                modifier = Modifier.testTag("watch_delivery_${message.requestId}"))
                            if (acknowledged) Text("Phone receipt confirmed · separate from reply", style = MaterialTheme.typography.caption2)
                            message.reply?.let { reply ->
                                Text(if (message.replyProvenance == ReplyProvenance.LOCAL_SYNTHETIC) "Synthetic fixture" else "Local bridge · dot unverified", color = CompanionCoral, style = MaterialTheme.typography.caption1)
                                Text(reply, style = MaterialTheme.typography.body2)
                            }
                        }
                    }
                    if (message.status == MessageStatus.FAILED && message.failureKind == FailureKind.TRANSPORT &&
                        message.requestId !in ui.saved.phoneAcknowledgedIds && message.requestId !in ui.saved.phoneReportedFailureIds) {
                        item { Chip(onClick = { onRetry(message.requestId) }, label = { Text("Retry transport") }, modifier = Modifier.fillMaxWidth()) }
                    }
                }
                item { Text("Voice / push to talk unavailable", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center, modifier = Modifier.testTag("watch_voice_unavailable")) }
                if (ui.saved.state.mode != ConnectionMode.LOCAL_PREVIEW) {
                    item { Chip(onClick = { onMode(ConnectionMode.LOCAL_PREVIEW) }, label = { Text("Local preview") }, secondaryLabel = { Text("Synthetic only") }, modifier = Modifier.fillMaxWidth()) }
                }
                item { Chip(onClick = { onMode(ConnectionMode.DISCONNECTED) }, label = { Text("Disconnect") }, modifier = Modifier.fillMaxWidth()) }
                item { Chip(onClick = { deleting = true }, label = { Text("Delete watch history") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("Unofficial community project", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
            }
        }
    }
}
