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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
fun SpeechSetupSheet(capability: String, dismiss: () -> Unit) {
    val models = koinInject<ModelManager>()
    val playback = koinInject<PlaybackCoordinator>()
    val state by models.state.collectAsStateWithLifecycle()
    val audio by playback.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).navigationBarsPadding().padding(horizontal = 24.dp)) {
            SheetHeader(when (capability) { "tts" -> "Reading voice"; "asr" -> "Transcribe voice notes"; else -> "Voices and transcription" }, done = dismiss)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (state.initializing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                for (category in if (capability == "all") listOf("tts", "asr") else listOf(capability)) {
                    if (capability == "all") item { Text(if (category == "tts") "Reading voice" else "Transcribe voice notes", style = MaterialTheme.typography.titleMedium) }
                    items(state.catalog.filter { it.capability == category }, key = { it.id }) { pack ->
                        SpeechPackCard(pack, state, models, audio.playing || audio.capturing || audio.preparing, audio.capturing)
                    }
                }
            }
        }
    }
}

internal fun speechSetupStatus(capability: String, state: ModelState, models: ModelManager): String {
    val intent = state.pendingActivation[capability]
    if (intent != null) {
        val install = state.installs.firstOrNull { it.packId == intent.packId }
        return when {
            intent.paused || install?.status == "paused" -> "Paused"
            install?.status == "downloading" -> "Downloading…"
            intent.packId in state.checking || install?.status == "verifying" -> "Checking…"
            install?.status == "failed" -> "Try again"
            capability in state.activating -> "Finishing setup…"
            state.activationMessage[capability] != null -> state.activationMessage.getValue(capability)
            else -> "Ready to set up"
        }
    }
    return if (capability == "tts") {
        if (models.installed(state.selectedVoice) != null) "Phonon · English" else "Not downloaded"
    } else if (models.asrDirectory() != null) "On" else "Off"
}

@Composable
private fun SpeechPackCard(pack: ModelPack, state: ModelState, models: ModelManager, verificationBlocked: Boolean, capturing: Boolean) {
    val view = LocalView.current
    val locale = LocalLocale.current.platformLocale
    var details by androidx.compose.runtime.saveable.rememberSaveable(pack.id) { mutableStateOf(false) }
    val install = state.installs.firstOrNull { it.packId == pack.id }
    val status = install?.status ?: "notInstalled"
    val checking = pack.id in state.checking || status == "verifying"
    val removing = pack.id in state.removing
    val available = models.installed(pack.id) != null
    val active = available && if (pack.capability == "tts") state.selectedVoice == pack.id else state.selectedTranscription == pack.id
    val pending = state.pendingActivation[pack.capability]?.takeIf { it.packId == pack.id }
    val activating = pending != null && pack.capability in state.activating
    val reason = state.unavailable[pack.id] ?: if (available) null else models.compatibility(pack)
    val languages = pack.languageTags.map { Locale.forLanguageTag(it).getDisplayLanguage(locale) }.distinct().joinToString()
    val size = String.format(locale, "%.1f MB", pack.totalBytes / 1_000_000.0)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (pack.capability == "tts") "Phonon" else pack.name, style = MaterialTheme.typography.titleLarge)
            Text("$languages · $size", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (verificationBlocked || status == "verifying") Text(when { capturing -> "Finish recording to continue"; verificationBlocked -> "Waiting for playback to pause"; else -> "Checking…" }, style = MaterialTheme.typography.bodySmall)
            } else if (status == "downloading") {
                LinearProgressIndicator(progress = { (install!!.bytesDownloaded.toFloat() / pack.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(String.format(locale, "%.1f of %s", install!!.bytesDownloaded / 1_000_000.0, size), style = MaterialTheme.typography.bodySmall)
            }
            if (pending != null && !checking && status != "downloading") state.activationMessage[pack.capability]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (status == "failed" && install!!.error.isNotBlank()) Text(install.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!available && reason != null) Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val label = when {
                removing -> "Removing…"
                activating -> "Finishing setup…"
                checking && status != "verifying" -> "Checking…"
                status in setOf("downloading", "verifying") -> if (pending != null) "Pause" else if (pack.capability == "tts") "Download & use" else "Download & enable"
                pending?.paused == true || status == "paused" -> "Resume"
                active -> if (pack.capability == "tts") "Selected" else "On"
                available -> if (pack.capability == "tts") "Use voice" else "Enable"
                status == "failed" -> "Retry"
                else -> if (pack.capability == "tts") "Download & use" else "Download & enable"
            }
            Button(onClick = {
                if (status in setOf("downloading", "verifying") && pending != null) models.pause(pack.id) else models.setup(pack.id)
                haptic(view)
            }, enabled = !removing && !activating && !state.initializing && !state.refreshing && (!checking || status == "verifying") && (!active || pending?.paused == true) && (reason == null || status in setOf("downloading", "verifying")), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(label)
            }
            TextButton(onClick = { details = !details; haptic(view) }, modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (details) "Expanded" else "Collapsed" }) {
                Text("Details")
                Spacer(Modifier.width(6.dp))
                Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (details) {
                Text(pack.description, style = MaterialTheme.typography.bodySmall)
                Text("${pack.runtime} · ${pack.version}", style = MaterialTheme.typography.bodySmall)
                val memory = if (pack.minimumMemoryMB % 1024 == 0) "${pack.minimumMemoryMB / 1024} GB" else "${pack.minimumMemoryMB} MB"
                val storage = (pack.totalBytes + 32L * 1024 * 1024 + 999_999) / 1_000_000
                Text("Android ${if (pack.minimumOS == 28) "9+" else "API ${pack.minimumOS}+"} · $memory RAM · $storage MB free storage", style = MaterialTheme.typography.bodySmall)
                state.checks[pack.id]?.let { Text(String.format(locale, "Device check passed in %.1f s", it / 1000.0), style = MaterialTheme.typography.bodySmall) }
                if (available && !removing && !checking) TextButton(onClick = { models.checkOnDevice(pack.id); haptic(view) }, enabled = !verificationBlocked && !state.refreshing) { Text("Check on this device") }
                if (install != null && !removing && !checking && status != "downloading") TextButton(onClick = { models.remove(pack.id); haptic(view) }, enabled = !capturing && !state.changingTranscription) { Text("Remove downloaded files") }
                if (models.catalogRefreshConfigured) {
                    TextButton(onClick = { models.refreshCatalog(); haptic(view) }, enabled = !state.refreshing && !state.initializing) { Text(if (state.refreshing) "Refreshing catalogue…" else "Refresh catalogue") }
                    state.catalogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
