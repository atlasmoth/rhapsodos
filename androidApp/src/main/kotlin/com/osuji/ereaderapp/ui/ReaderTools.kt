package com.osuji.ereaderapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osuji.ereaderapp.audio.PlaybackCoordinator
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.data.SearchMatch
import com.osuji.ereaderapp.models.ModelManager
import com.osuji.ereaderapp.data.ModelPack
import com.osuji.ereaderapp.models.ModelState
import java.util.Locale
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
    val audio by playback.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    fun chooseTranscription(packId: String) {
        selectionScope.launch {
            runCatching { models.selectTranscription(packId); haptic(view) }
                .onFailure { models.reportError(it.message ?: "Transcription could not be selected.") }
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).navigationBarsPadding().padding(horizontal = 24.dp)) {
            SheetHeader("Offline voices & transcription", done = dismiss)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item { Text("Downloads contain public model files. Your documents, recordings and words stay on your device.") }
                item {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (state.catalogStatus.source == "bundled") "Bundled catalogue" else "Catalogue revision ${state.catalogStatus.revision}", style = MaterialTheme.typography.titleMedium)
                            if (models.catalogRefreshConfigured) {
                                OutlinedButton(onClick = { models.refreshCatalog(); haptic(view) }, enabled = !state.refreshing && !state.initializing) {
                                    Text(if (state.refreshing) "Refreshing catalogue…" else "Refresh catalogue")
                                }
                            } else Text("Live catalogue updates are not configured yet.", style = MaterialTheme.typography.bodySmall)
                            state.catalogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (state.refreshing || state.initializing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                item { Text("Narration", style = MaterialTheme.typography.titleLarge) }
                items(state.catalog.filter { it.capability == "tts" }, key = { it.id }) { pack ->
                    SpeechPackCard(pack, state, models, playback, selectionScope, ::chooseTranscription)
                }
                item { Text("Voice-note transcription", style = MaterialTheme.typography.titleLarge) }
                item {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Recordings stay saved with transcription off. Choose a checked pack to recognize your words locally.", style = MaterialTheme.typography.bodySmall)
                            FilterChip(selected = state.selectedTranscription == "off", onClick = { chooseTranscription("off") }, label = { Text("Off") }, enabled = !state.changingTranscription && !audio.capturing)
                        }
                    }
                }
                items(state.catalog.filter { it.capability == "asr" }, key = { it.id }) { pack ->
                    SpeechPackCard(pack, state, models, playback, selectionScope, ::chooseTranscription)
                }
            }
        }
    }
}

@Composable
private fun SpeechPackCard(pack: ModelPack, state: ModelState, models: ModelManager, playback: PlaybackCoordinator, selectionScope: CoroutineScope, chooseTranscription: (String) -> Unit) {
    val view = LocalView.current
    val audio by playback.state.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val install = state.installs.firstOrNull { it.packId == pack.id }
    val status = install?.status ?: "notInstalled"
    val checking = pack.id in state.checking || status == "verifying"
    val removing = pack.id in state.removing
    val available = models.installed(pack.id) != null
    val active = available && if (pack.capability == "tts") state.selectedVoice == pack.id else state.selectedTranscription == pack.id
    val reason = state.unavailable[pack.id] ?: models.compatibility(pack)
    val label = when {
        removing -> "Removing"
        checking -> "Checking"
        active -> "Active"
        status == "ready" && available -> "Ready"
        status == "ready" -> "Unavailable"
        status == "downloading" -> "Downloading"
        status == "paused" -> "Paused"
        status == "failed" -> "Failed"
        else -> "Download"
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
            Text(pack.description)
            val languages = pack.languageTags.joinToString { Locale.forLanguageTag(it).getDisplayLanguage(locale) }
            Text("${pack.totalBytes / (1024 * 1024)} MB · $languages", style = MaterialTheme.typography.bodySmall)
            val memory = if (pack.minimumMemoryMB % 1024 == 0) "${pack.minimumMemoryMB / 1024} GB" else "${pack.minimumMemoryMB} MB"
            val requiredStorage = (pack.totalBytes + 32L * 1024 * 1024 + 1024 * 1024 - 1) / (1024 * 1024)
            Text("Android ${if (pack.minimumOS == 28) "9+" else "API ${pack.minimumOS}+"} · $memory RAM · $requiredStorage MB free storage", style = MaterialTheme.typography.bodySmall)
            if (checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking files and running a quiet check on this device…", style = MaterialTheme.typography.bodySmall)
            } else if (status == "downloading") {
                LinearProgressIndicator(progress = { (install!!.bytesDownloaded.toFloat() / pack.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${install!!.bytesDownloaded / (1024 * 1024)} of ${pack.totalBytes / (1024 * 1024)} MB downloaded", style = MaterialTheme.typography.bodySmall)
            } else if (available) {
                val seconds = state.checks[pack.id]?.let { String.format(locale, "%.1f", it / 1000.0) }
                Text(if (seconds == null) "Checked on this device. Longer-session performance varies." else "Device check passed in $seconds s. Longer-session performance varies.", style = MaterialTheme.typography.bodySmall)
            }
            if (available && !removing && pack.capability == "tts") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                models.voiceIds(pack.id).forEach { voice ->
                    FilterChip(state.selectedVoice == pack.id && state.voiceId == voice, onClick = { selectionScope.launch { runCatching { playback.selectVoice(models, pack.id, voice); haptic(view) }.onFailure { models.reportError(it.message ?: "This voice could not be selected.") } } }, label = { Text(models.voiceName(pack.id, voice)) }, enabled = !audio.capturing)
                }
            }
            if (status == "failed" && install!!.error.isNotBlank()) Text(install.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!available && reason != null) Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    removing -> Text("Removing this download…", style = MaterialTheme.typography.labelLarge)
                    checking -> if (status == "verifying") OutlinedButton(onClick = { models.pause(pack.id); haptic(view) }) { Text("Pause check") }
                    available && pack.capability == "tts" -> Button(onClick = { selectionScope.launch { runCatching { playback.selectVoice(models, pack.id); haptic(view) }.onFailure { models.reportError(it.message ?: "This voice could not be selected.") } } }, enabled = !audio.capturing) { Text(if (active) "Active" else "Use this voice") }
                    available -> Button(onClick = { chooseTranscription(pack.id) }, enabled = !state.changingTranscription && !audio.capturing) { Text(if (active) "Active" else "Use for voice notes") }
                    status == "downloading" -> OutlinedButton(onClick = { models.pause(pack.id); haptic(view) }) { Text("Pause download") }
                    status == "ready" -> Text("This download cannot run on this device.", style = MaterialTheme.typography.bodySmall)
                    else -> Button(onClick = { models.download(pack.id); haptic(view) }, enabled = reason == null && !state.initializing && !state.refreshing) { Text(if (status == "paused") "Resume download" else if (status == "failed") "Retry download" else "Download") }
                }
                if (install != null && !removing && !checking && status != "downloading") TextButton(onClick = { models.remove(pack.id); haptic(view) }, enabled = !audio.capturing && !state.changingTranscription) { Text("Remove downloaded files") }
                if (available && !removing && !checking) TextButton(onClick = { models.checkOnDevice(pack.id); haptic(view) }, enabled = !audio.capturing && !state.refreshing) { Text("Check on this device") }
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
