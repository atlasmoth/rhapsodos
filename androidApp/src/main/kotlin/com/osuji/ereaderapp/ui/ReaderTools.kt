package com.osuji.ereaderapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osuji.ereaderapp.audio.PlaybackCoordinator
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.data.SearchMatch
import com.osuji.ereaderapp.models.ModelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun SheetHeader(title: String, doneEnabled: Boolean = true, done: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = done, enabled = doneEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineSpeechSheet(selectionScope: CoroutineScope, dismiss: () -> Unit) {
    val models = koinInject<ModelManager>()
    val playback = koinInject<PlaybackCoordinator>()
    val state by models.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).navigationBarsPadding().padding(horizontal = 24.dp)) {
        SheetHeader("Offline voices & transcription", done = dismiss)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Text("Downloads contain public model files. Your documents, recordings and words stay on your device.") }
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Installed Android voice", style = MaterialTheme.typography.titleMedium)
                        Text("Offline availability is checked when listening starts.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { selectionScope.launch { runCatching { playback.selectVoice(models, "system"); haptic(view) }.onFailure { models.reportError(it.message ?: "This voice could not be selected.") } } }, enabled = !playback.capturing) { Text(if (state.selectedVoice == "system") "Selected" else "Use system voice") }
                            TextButton(onClick = { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }) { Text("Android voice settings") }
                        }
                    }
                }
            }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            items(state.catalog, key = { it.id }) { pack ->
                val install = state.installs.firstOrNull { it.packId == pack.id }
                val status = install?.status ?: "notInstalled"
                val checking = pack.id in state.checking
                val removing = pack.id in state.removing
                val reason = models.compatibility(pack)
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(pack.name, style = MaterialTheme.typography.titleLarge)
                        Text(pack.description)
                        Text("${pack.totalBytes / (1024 * 1024)} MB · English · ${if (pack.capability == "tts") "Narration" else "Voice-note transcription"}", style = MaterialTheme.typography.bodySmall)
                        if (status in setOf("downloading", "verifying")) {
                            LinearProgressIndicator(progress = { (install!!.bytesDownloaded.toFloat() / pack.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(if (status == "verifying") "Checking files and trying the model on this device…" else "${install!!.bytesDownloaded / (1024 * 1024)} of ${pack.totalBytes / (1024 * 1024)} MB downloaded", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!removing && status == "ready" && pack.capability == "tts") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("jasper", "bella", "bruno", "luna", "hugo", "rosie", "leo", "kiki").forEach { voice ->
                                FilterChip(state.selectedVoice == pack.id && state.voiceId == voice, onClick = { selectionScope.launch { runCatching { playback.selectVoice(models, pack.id, voice); haptic(view) }.onFailure { models.reportError(it.message ?: "This voice could not be selected.") } } }, label = { Text(voice.replaceFirstChar { it.uppercase() }) }, enabled = !checking && !playback.capturing)
                            }
                        }
                        if (status == "failed" && install!!.error.isNotBlank()) Text(install.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (status != "ready" && reason != null) Text(reason, color = MaterialTheme.colorScheme.error)
                        if (checking) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking installed files on this device…", style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (if (removing) "removing" else status) {
                                "removing" -> Text("Removing this download…", style = MaterialTheme.typography.labelLarge)
                                "ready" -> {
                                    if (pack.capability == "tts") Button(onClick = { selectionScope.launch { runCatching { playback.selectVoice(models, pack.id); haptic(view) }.onFailure { models.reportError(it.message ?: "This voice could not be selected.") } } }, enabled = !playback.capturing && !checking) { Text(if (state.selectedVoice == pack.id) "Selected" else "Use this voice") }
                                    else Text("Ready for saved voice notes", style = MaterialTheme.typography.labelLarge)
                                }
                                "downloading", "verifying" -> OutlinedButton(onClick = { models.pause(pack.id); haptic(view) }) { Text("Pause download") }
                                else -> Button(onClick = { models.download(pack.id); haptic(view) }, enabled = reason == null) { Text(if (status == "paused") "Resume download" else if (status == "failed") "Retry download" else "Download") }
                            }
                            if (install != null && !removing && status !in setOf("downloading", "verifying")) TextButton(onClick = { models.remove(pack.id) }, enabled = !playback.capturing && !checking) { Text("Remove downloaded files") }
                            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pack.licenseUrl))) } }) { Text("License") }
                        }
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(documentId: String, select: suspend (SearchMatch) -> Boolean, dismiss: () -> Unit) {
    val store = koinInject<ReaderStore>()
    val scope = rememberCoroutineScope()
    var query by remember(documentId) { mutableStateOf("") }
    var results by remember(documentId) { mutableStateOf<List<SearchMatch>>(emptyList()) }
    var searching by remember(documentId) { mutableStateOf(false) }
    var searchError by remember(documentId) { mutableStateOf<String?>(null) }
    var navigationError by remember(documentId) { mutableStateOf<String?>(null) }
    var failedTarget by remember(documentId) { mutableStateOf<SearchMatch?>(null) }
    var selecting by remember(documentId) { mutableStateOf(false) }
    var searchAttempt by remember(documentId) { mutableIntStateOf(0) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !selecting })
    LaunchedEffect(documentId, query, searchAttempt) {
        val expectedQuery = query
        val expectedAttempt = searchAttempt
        results = emptyList()
        searchError = null
        if (query.isBlank()) { searching = false; return@LaunchedEffect }
        searching = true
        try {
            delay(200)
            val matches = store.search(documentId, expectedQuery)
            if (query == expectedQuery && searchAttempt == expectedAttempt) results = matches
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (query == expectedQuery && searchAttempt == expectedAttempt) searchError = "Search could not finish. Try again."
        } finally {
            if (query == expectedQuery && searchAttempt == expectedAttempt) searching = false
        }
    }
    fun open(hit: SearchMatch) {
        if (selecting || documentId.isNotBlank() && hit.documentId != documentId) return
        val expectedQuery = query
        val expectedAttempt = searchAttempt
        selecting = true
        navigationError = null
        failedTarget = null
        scope.launch {
            try {
                val accepted = select(hit)
                if (query == expectedQuery && searchAttempt == expectedAttempt) {
                    if (accepted) dismiss()
                    else { failedTarget = hit; navigationError = "This passage could not be opened. Finish recording or playing your note, then try again." }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (query == expectedQuery && searchAttempt == expectedAttempt) {
                    failedTarget = hit
                    navigationError = "This passage could not be opened. Try again."
                }
            } finally { selecting = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!selecting) dismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).fillMaxHeight(0.9f).padding(24.dp)) {
            SheetHeader(if (documentId.isBlank()) "Search your library" else "Find a passage", doneEnabled = !selecting, done = dismiss)
            OutlinedTextField(query, {
                query = it
                searchAttempt++
                results = emptyList()
                searchError = null
                navigationError = null
                failedTarget = null
                searching = it.isNotBlank()
            }, modifier = Modifier.fillMaxWidth(), enabled = !selecting, singleLine = true, label = { Text("Words or a phrase") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (searching || selecting) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (selecting) item { Text("Opening passage…", style = MaterialTheme.typography.bodySmall) }
                searchError?.let { error -> item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { searchAttempt++; results = emptyList(); searching = true }, enabled = !selecting) { Text("Retry") }
                    }
                } }
                navigationError?.let { error -> item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { failedTarget?.let { open(it) } }, enabled = !selecting) { Text("Retry") }
                    }
                } }
                if (query.isBlank()) item { Text("Enter words or a phrase to search prepared passages and your thoughts.", Modifier.padding(vertical = 20.dp)) }
                else if (!searching && searchError == null && results.isEmpty()) item { Text("No matches yet. Prepared pages and your thoughts are searched locally.", Modifier.padding(vertical = 20.dp)) }
                items(results, key = { "${it.kind}:${it.id}" }) { hit ->
                    Surface(onClick = { open(hit) }, enabled = !selecting, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(hit.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Page ${hit.pageIndex + 1} · ${if (hit.kind == "note") "Note" else "Passage"}", style = MaterialTheme.typography.labelSmall)
                            Text(hit.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
