package dev.dotcompanion.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.dotcompanion.shared.CompanionNight
import dev.dotcompanion.shared.LicenseLoadState
import dev.dotcompanion.shared.licenseTextChunks

@Composable
fun PhoneLicenseScreen(state: LicenseLoadState, onBack: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val catalog = (state as? LicenseLoadState.Available)?.catalog
    val selected = catalog?.entries?.firstOrNull { it.id == selectedId }
    val catalogScroll = rememberLazyListState()
    val textScroll = remember(selectedId) { LazyListState() }
    val back = { if (selectedId == null) onBack() else selectedId = null }
    BackHandler(onBack = back)
    val clipboard = LocalClipboardManager.current
    var copyIssue by remember(selectedId) { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize().testTag("phone_license_screen"), color = CompanionNight) {
        Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, modifier = Modifier.testTag("phone_license_back")) { Text("Back") }
                Text(if (selected == null) "Licenses" else "License text", style = MaterialTheme.typography.titleLarge)
            }
            if (selected != null) {
                val chunks = remember(selected.text) { licenseTextChunks(selected.text) }
                val canCopyFullText = remember(selected.text) { selected.text.toByteArray(Charsets.UTF_8).size <= 128 * 1024 }
                OutlinedButton(onClick = {
                    try { clipboard.setText(AnnotatedString(selected.text)); copyIssue = null }
                    catch (_: Exception) { copyIssue = "Copy unavailable. Select text to copy a section." }
                }, enabled = canCopyFullText, modifier = Modifier.testTag("phone_license_copy")) { Text("Copy full text") }
                if (!canCopyFullText) Text("Select text to copy smaller sections.", style = MaterialTheme.typography.bodySmall)
                copyIssue?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("phone_license_text"), state = textScroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Text(selected.title, style = MaterialTheme.typography.titleMedium) }
                        items(chunks.size) { index -> Text(chunks[index], style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().testTag("phone_license_text_$index")) }
                    }
                }
            } else when (state) {
                LicenseLoadState.Loading -> Text("Loading packaged licenses…", modifier = Modifier.testTag("phone_license_loading"))
                LicenseLoadState.Unavailable -> Text("Packaged licenses are unavailable in this build.", modifier = Modifier.testTag("phone_license_error"))
                is LicenseLoadState.Available -> {
                    Text("Open-source notices · available offline", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("phone_license_list"), state = catalogScroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.catalog.entries, key = { it.id }) { entry ->
                            OutlinedButton(onClick = { selectedId = entry.id }, modifier = Modifier.fillMaxWidth()) { Text(entry.title) }
                        }
                    }
                }
            }
        }
    }
}
