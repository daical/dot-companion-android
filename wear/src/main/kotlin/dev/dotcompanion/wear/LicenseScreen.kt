package dev.dotcompanion.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.dotcompanion.shared.CompanionNight
import dev.dotcompanion.shared.LicenseLoadState
import dev.dotcompanion.shared.licenseTextChunks

@Composable
fun WatchLicenseScreen(state: LicenseLoadState, onBack: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val catalog = (state as? LicenseLoadState.Available)?.catalog
    val selected = catalog?.entries?.firstOrNull { it.id == selectedId }
    val catalogScroll = rememberLazyListState()
    val textScroll = remember(selectedId) { LazyListState() }
    val back = { if (selectedId == null) onBack() else selectedId = null }
    BackHandler(onBack = back)
    val clipboard = LocalClipboardManager.current
    var copyIssue by remember(selectedId) { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current
    val shape = if (configuration.isScreenRound) Modifier.clip(CircleShape) else Modifier
    val horizontalInset = if (configuration.isScreenRound) (configuration.screenWidthDp * .15f).dp else 16.dp
    val verticalInset = if (configuration.isScreenRound) (configuration.screenHeightDp * .15f).dp else 24.dp
    Scaffold(Modifier.fillMaxSize().then(shape).background(CompanionNight).testTag("watch_license_screen"), timeText = { TimeText() },
        positionIndicator = { if (state is LicenseLoadState.Available) PositionIndicator(lazyListState = if (selected == null) catalogScroll else textScroll) }) {
        // A bounded rectangular reading viewport stays inside the round bezel. Long
        // license text scrolls without the scaling used for the companion's controls.
        Column(Modifier.fillMaxSize().padding(horizontal = horizontalInset, vertical = verticalInset), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip(onClick = back, label = { Text("Back") }, modifier = Modifier.fillMaxWidth().testTag("watch_license_back"))
            if (selected != null) {
                val chunks = remember(selected.text) { licenseTextChunks(selected.text) }
                val canCopyFullText = remember(selected.text) { selected.text.toByteArray(Charsets.UTF_8).size <= 128 * 1024 }
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("watch_license_text"), state = textScroll, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { Text(selected.title, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center) }
                        item { Chip(onClick = {
                            try { clipboard.setText(AnnotatedString(selected.text)); copyIssue = null }
                            catch (_: Exception) { copyIssue = "Copy unavailable. Select a section instead." }
                        }, enabled = canCopyFullText, label = { Text("Copy full text") }, modifier = Modifier.fillMaxWidth().testTag("watch_license_copy")) }
                        if (!canCopyFullText) item { Text("Select text to copy smaller sections.", fontSize = 12.sp, lineHeight = 16.sp) }
                        copyIssue?.let { issue -> item { Text(issue, fontSize = 12.sp, lineHeight = 16.sp) } }
                        items(chunks.size) { index -> Text(chunks[index], fontSize = 12.sp, lineHeight = 16.sp,
                            modifier = Modifier.fillMaxWidth().testTag("watch_license_text_$index")) }
                    }
                }
            } else when (state) {
                LicenseLoadState.Loading -> Text("Loading licenses…", fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.testTag("watch_license_loading"))
                LicenseLoadState.Unavailable -> Text("Packaged licenses are unavailable in this build.", fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.testTag("watch_license_error"))
                is LicenseLoadState.Available -> LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("watch_license_list"), state = catalogScroll, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Text("Licenses · offline", fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center) }
                    items(state.catalog.entries, key = { it.id }) { entry ->
                        Chip(onClick = { selectedId = entry.id }, label = { Text(entry.title, fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
