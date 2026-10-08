package com.osuji.ereaderapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osuji.ereaderapp.audio.PlaybackCoordinator
import com.osuji.ereaderapp.data.ReaderContents
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.data.SearchMatch
import com.osuji.ereaderapp.models.ModelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineSpeechSheet(dismiss: () -> Unit) {
    val models = koinInject<ModelManager>()
    val playback = koinInject<PlaybackCoordinator>()
    val state by models.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = dismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text("OFFLINE VOICES & TRANSCRIPTION", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Text("Make it sound like you", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text("Downloads contain public model files. Your documents, recordings and words stay on your device.")
            }
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Installed Android voice", style = MaterialTheme.typography.titleMedium)
                        Text("Offline availability is checked when listening starts.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { scope.launch { runCatching { playback.selectVoice(models, "system"); haptic(view) }.onFailure { models.reportError(it.message ?: "This voice could not be selected.") } } }, enabled = !playback.capturing) { Text(if (state.selectedVoice == "system") "Selected" else "Use system voice") }
                            TextButton(onClick = { playback.previewVoice("system") }, enabled = !playback.capturing) { Text("Listen to a sample") }
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
                                FilterChip(state.selectedVoice == pack.id && state.voiceId == voice, onClick = { scope.launch { runCatching { playback.selectVoice(models, pack.id, voice); haptic(view) }.onFailure { models.reportError(it.message ?: "This voice could not be selected.") } } }, label = { Text(voice.replaceFirstChar { it.uppercase() }) }, enabled = !checking && !playback.capturing)
                            }
                        }
                        if (status == "failed" && install!!.error.isNotBlank()) Text(install.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (status != "ready" && reason != null) Text(reason, color = MaterialTheme.colorScheme.error)
                        if (checking) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking installed files on this device…", style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (if (removing) "removing" else status) {
                                "removing" -> Text("Removing this download…", style = MaterialTheme.typography.labelLarge)
                                "ready" -> {
                                    if (pack.capability == "tts") Button(onClick = { scope.launch { runCatching { playback.selectVoice(models, pack.id); haptic(view) }.onFailure { models.reportError(it.message ?: "This voice could not be selected.") } } }, enabled = !playback.capturing && !checking) { Text(if (state.selectedVoice == pack.id) "Selected" else "Use this voice") }
                                    else Text("Ready for saved voice notes", style = MaterialTheme.typography.labelLarge)
                                    if (pack.capability == "tts") TextButton(onClick = { playback.previewVoice(pack.id) }, enabled = !playback.capturing && !checking) { Text("Listen to a sample") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(documentId: String, select: (SearchMatch) -> Unit, dismiss: () -> Unit) {
    val store = koinInject<ReaderStore>()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchMatch>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(documentId, query) {
        error = null
        if (query.isBlank()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        try {
            delay(200)
            results = store.search(documentId, query)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            results = emptyList()
            error = "Search could not finish. Try again."
        } finally { searching = false }
    }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(if (documentId.isBlank()) "Search your library" else "Find a passage", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Words or a phrase") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            Spacer(Modifier.height(16.dp))
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!searching && query.isNotBlank() && results.isEmpty()) item { Text("No matches yet. Prepared pages and your thoughts are searched locally.", Modifier.padding(vertical = 20.dp)) }
                items(results, key = { "${it.kind}:${it.id}" }) { hit ->
                    Surface(onClick = { select(hit); dismiss() }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(hit.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Page ${hit.pageIndex + 1} · ${if (hit.kind == "note") "Your thought" else "Passage"}", style = MaterialTheme.typography.labelSmall)
                            Text(hit.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PronunciationDialog(document: ReaderContents, changed: (ReaderContents) -> Unit, dismiss: () -> Unit) {
    val store = koinInject<ReaderStore>()
    val playback = koinInject<PlaybackCoordinator>()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var term by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!working) dismiss() }, title = { Text("Say it your way") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Change how a name or term sounds. The original text and every thought stay in place. Listening remains paused until you resume.")
            OutlinedTextField(term, { term = it }, enabled = !working, singleLine = true, label = { Text("Name or term in the PDF") })
            OutlinedTextField(replacement, { replacement = it }, enabled = !working, label = { Text("Say it like this") })
            if (document.pronunciations.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 160.dp)) {
                items(document.pronunciations, key = { it.id }) { rule ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${rule.term} → ${rule.replacement}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(enabled = !working && !playback.capturing, onClick = {
                            working = true
                            scope.launch {
                                try { playback.pause(); val updated = store.removePronunciation(rule.id); playback.reloadSpokenEdition(updated); changed(updated); haptic(view, true) }
                                catch (failure: Exception) { error = "This pronunciation could not be removed." }
                                finally { working = false }
                            }
                        }) { Icon(Icons.Default.DeleteOutline, "Remove pronunciation") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !working && !playback.capturing && term.isNotBlank() && replacement.isNotBlank(), onClick = {
            working = true
            scope.launch {
                try {
                    playback.pause()
                    val updated = store.savePronunciation(java.util.UUID.randomUUID().toString(), document.document.id, term, replacement)
                    playback.reloadSpokenEdition(updated); changed(updated); haptic(view, true); term = ""; replacement = ""
                } catch (failure: Exception) { error = failure.message ?: "This pronunciation could not be saved." }
                finally { working = false }
            }
        }) { Text(if (working) "Saving…" else "Save pronunciation") }
    }, dismissButton = { TextButton(enabled = !working, onClick = dismiss) { Text("Done") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(dismiss: () -> Unit) {
    val context = LocalContext.current
    val licenses = remember { context.assets.list("licenses").orEmpty().sorted() }
    var license by remember { mutableStateOf<Pair<String, String>?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Rhapsodos", style = MaterialTheme.typography.displaySmall); Text("Version 0.01 · build 1", style = MaterialTheme.typography.labelSmall) }
            item { Text("Your reading, on your device", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text("PDFs, source text, voice recordings, transcripts, bookmarks and listening positions remain in private app storage. No account, content upload, analytics or cloud speech fallback is used.") }
            item { Text("Public downloads", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text("Network access downloads the offline models you choose from their pinned public sources. Download hosts can see a network request and your IP address. Your PDF text, note audio, transcripts and filenames are never part of that request.") }
            item { Text("Your controls", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text("An exported archive contains an unencrypted copy of your PDF, thoughts and recordings. Choose a destination you trust. Deleting a document removes its local imported file, recordings and reading data. Generated narration is a disposable cache; removing a speech download leaves your thoughts intact.") }
            item { Text("Offline speech and font notices", style = MaterialTheme.typography.titleLarge) }
            items(licenses) { name -> TextButton(onClick = { license = name.removeSuffix(".txt").replace('_', ' ') to context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }) { Text(name.removeSuffix(".txt").replace('_', ' ')) } }
        }
    }
    license?.let { selected -> AlertDialog(onDismissRequest = { license = null }, title = { Text(selected.first) }, text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(selected.second, modifier = Modifier.heightIn(max = 440.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { license = null }) { Text("Close") } }) }
}
