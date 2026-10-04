package com.osuji.ereaderapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import com.osuji.ereaderapp.R
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.osuji.ereaderapp.audio.PlaybackCoordinator
import com.osuji.ereaderapp.audio.PlaybackSnapshot
import com.osuji.ereaderapp.audio.VoiceNoteCoordinator
import com.osuji.ereaderapp.data.LibraryDocument
import com.osuji.ereaderapp.data.ReaderContents
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.data.VoiceNote
import com.osuji.ereaderapp.data.DocumentArchives
import com.osuji.ereaderapp.data.OwnedFiles
import com.osuji.ereaderapp.data.ReaderPassage
import com.osuji.ereaderapp.data.Bookmark
import com.osuji.ereaderapp.pdf.DocumentPreparation
import com.osuji.ereaderapp.audio.TranscriptionCoordinator
import com.osuji.ereaderapp.pdf.PdfAdapter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable data object LibraryKey : NavKey
@Serializable data class DocumentKey(val id: String) : NavKey

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ReaderApp() {
    val store = koinInject<ReaderStore>()
    val pdf = koinInject<PdfAdapter>()
    val playback = koinInject<PlaybackCoordinator>()
    val capture = koinInject<VoiceNoteCoordinator>()
    val preparation = koinInject<DocumentPreparation>()
    val files = koinInject<OwnedFiles>()
    val archives = koinInject<DocumentArchives>()
    val library by store.snapshot.collectAsStateWithLifecycle()
    val audio by playback.state.collectAsStateWithLifecycle()
    val noteCapture by capture.state.collectAsStateWithLifecycle()
    val recording by capture.recorder.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(capture, lifecycleOwner, view) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            capture.savedEvents.collect { haptic(view, true) }
        }
    }
    val backStack = rememberNavBackStack(LibraryKey)
    val sceneStrategy = rememberListDetailSceneStrategy<NavKey>()
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showVoices by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var libraryAction by remember { mutableStateOf<Pair<String, LibraryDocument>?>(null) }
    var renameText by remember { mutableStateOf("") }
    var actionSaving by remember { mutableStateOf(false) }
    var exportDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    val preferences = remember { context.getSharedPreferences("presentation", android.content.Context.MODE_PRIVATE) }
    var appearance by rememberSaveable { mutableStateOf(preferences.getString("appearance", "system") ?: "system") }
    var reducedMotion by rememberSaveable { mutableStateOf(preferences.getBoolean("reducedMotion", false)) }
    var touchFeedback by rememberSaveable { mutableStateOf(preferences.getBoolean("touchFeedback", true)) }
    var typedNote by rememberSaveable { mutableStateOf(false) }
    var typedText by rememberSaveable { mutableStateOf("") }
    var typedDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var typedPassageId by rememberSaveable { mutableStateOf<String?>(null) }
    var typedOffset by rememberSaveable { mutableLongStateOf(0) }
    var typedQuote by rememberSaveable { mutableStateOf("") }
    var typedPage by rememberSaveable { mutableIntStateOf(0) }
    var typedSaving by remember { mutableStateOf(false) }
    var typedError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(noteCapture.error) { if (noteCapture.draft == null && noteCapture.error != null) message = noteCapture.error }

    fun openDocument(id: String) {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack.add(DocumentKey(id))
        haptic(view)
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val imported = pdf.import(uri)
                store.begin(imported)
                preparation.prepare(imported.id)
                openDocument(imported.id)
                haptic(view, true)

            } catch (failure: Exception) {
                message = failure.message ?: "This PDF could not be imported. Please try another file."
            } finally { importing = false }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.rhapsodos.document+zip")) { uri ->
        val id = exportDocumentId
        if (uri != null && id != null) scope.launch {
            try { archives.export(id, uri); haptic(view, true); message = "Your PDF, thoughts, bookmarks and listening position were exported." }
            catch (failure: Exception) { message = failure.message ?: "The archive could not be saved." }
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { val id = archives.restore(uri); openDocument(id); haptic(view, true) }
            catch (failure: Exception) { message = failure.message ?: "The archive could not be restored." }
        }
    }
    LaunchedEffect(libraryAction) {
        val action = libraryAction ?: return@LaunchedEffect
        if (action.first == "export") { exportDocumentId = action.second.id; exportPicker.launch("${action.second.title}.rhapsodos"); libraryAction = null }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) { haptic(view, true); capture.begin() }
        else { typedNote = true; typedError = null; message = null }
    }
    fun recordThought() {
        val frozen = playback.beginCapture() ?: return
        typedDocumentId = frozen.documentId
        typedPassageId = frozen.passageId
        typedOffset = frozen.offsetMillis
        typedPage = audio.pageIndex
        typedQuote = ""
        scope.launch {
            runCatching { store.contents(frozen.documentId) }.onSuccess { contents ->
                if (typedDocumentId == frozen.documentId && typedPassageId == frozen.passageId) {
                    typedQuote = contents.passages.firstOrNull { it.id == frozen.passageId }?.sourceText.orEmpty()
                }
            }
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            haptic(view, true)
            capture.begin()
        } else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun writeThought(documentId: String, passage: ReaderPassage) {
        val frozen = playback.beginTextCapture(documentId, passage.id) ?: return
        typedDocumentId = documentId; typedPassageId = passage.id; typedOffset = frozen.offsetMillis
        typedQuote = passage.sourceText; typedPage = passage.pageIndex; typedError = null; typedNote = true
        haptic(view)
    }

    ReaderTheme(appearance) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactHeader = maxWidth < 500.dp
                Column {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_rhapsodos), null, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text("Rhapsodos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!compactHeader) {
                        IconButton(onClick = { showSearch = true; haptic(view) }) { Icon(Icons.Default.Search, "Search library") }
                        IconButton(onClick = { showVoices = true; haptic(view) }) { Icon(Icons.Default.Headphones, "Offline voices") }
                    }
                    IconButton(onClick = { showSettings = true; haptic(view) }) { Icon(Icons.Default.Tune, "Reading settings") }
                    if (compactHeader) FilledTonalIconButton(onClick = { importPicker.launch(arrayOf("application/pdf")); haptic(view) }, enabled = !importing) {
                        if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Add, "Import a PDF")
                    } else FilledTonalButton(onClick = { importPicker.launch(arrayOf("application/pdf")); haptic(view) }, enabled = !importing) {
                        if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (importing) "Importing" else "Import")
                    }
                }
                if (compactHeader) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showSearch = true; haptic(view) }) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Search") }
                    TextButton(onClick = { showVoices = true; haptic(view) }) { Icon(Icons.Default.Headphones, null); Spacer(Modifier.width(6.dp)); Text("Offline voices") }
                }
                }
                }
            },
            bottomBar = {
                if (audio.documentId != null) ListeningDock(audio, playback, !playback.capturing, ::recordThought, { showSleep = true })
            },
        ) { padding ->
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize().padding(padding),
                onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                sceneStrategies = listOf(sceneStrategy),
                entryProvider = entryProvider {
                    entry<LibraryKey>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { EmptyListeningRoom { importPicker.launch(arrayOf("application/pdf")) } })) {
                        LibraryPane(library.documents, audio, reducedMotion, importing, { importPicker.launch(arrayOf("application/pdf")) }, ::openDocument, { action, document -> libraryAction = action to document; renameText = document.title })
                    }
                    entry<DocumentKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                        DocumentPane(key.id, store, pdf, playback, library.notes.filter { it.documentId == key.id }, audio, reducedMotion, { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }, ::recordThought, { passage -> writeThought(key.id, passage) })
                    }
                },
            )
        }

        if (message != null) AlertDialog(onDismissRequest = { message = null }, title = { Text("A quick note") }, text = { Text(message.orEmpty()) }, confirmButton = { TextButton(onClick = { message = null }) { Text("Got it") } })

        if (showVoices) OfflineSpeechSheet { showVoices = false }
        if (showAbout) AboutSheet { showAbout = false }
        if (showSleep) SleepTimerDialog { showSleep = false }
        if (showSearch) SearchSheet("", { hit ->
            openDocument(hit.documentId)
            scope.launch {
                runCatching { store.contents(hit.documentId) }.onSuccess { document ->
                    val index = document.passages.indexOfFirst { it.id == hit.passageId }
                    if (index >= 0) playback.listen(document, index)
                }
            }
        }, { showSearch = false })

        if (showSettings) AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Make yourself comfortable") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Text follows your system size. The page view keeps the original PDF; passage view gives you comfortable, accessible type.")
                    Text("Appearance", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("system", "light", "dark").forEach { mode -> FilterChip(appearance == mode, { appearance = mode; preferences.edit().putString("appearance", mode).apply(); haptic(view) }, { Text(mode.replaceFirstChar { it.uppercase() }) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reduce decorative motion", Modifier.weight(1f))
                        Switch(reducedMotion, { reducedMotion = it; preferences.edit().putBoolean("reducedMotion", it).apply(); haptic(view) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Touch feedback", Modifier.weight(1f))
                        Switch(touchFeedback, { touchFeedback = it; preferences.edit().putBoolean("touchFeedback", it).apply(); haptic(view) })
                    }
                    OutlinedButton(onClick = { showSettings = false; restorePicker.launch(arrayOf("application/vnd.rhapsodos.document+zip", "application/zip", "application/octet-stream")) }) { Text("Restore an exported document") }
                    TextButton(onClick = { showSettings = false; showAbout = true }) { Text("About, privacy & offline notices") }
                    Text("Rhapsodos 0.01 · local content processing", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } },
        )

        if (noteCapture.draft != null) ModalBottomSheet(onDismissRequest = {}) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Label(if (recording.recording) "RECORDING YOUR THOUGHT" else "YOUR ANCHORED THOUGHT")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(clock(recording.elapsedMs), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.weight(1f))
                    if (recording.recording) Text("Recording", color = MaterialTheme.colorScheme.error)
                }
                LinearProgressIndicator(progress = { recording.level }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Attached to page ${noteCapture.draft!!.pageIndex + 1}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(noteCapture.draft!!.quote, maxLines = 4, overflow = TextOverflow.Ellipsis, fontFamily = ReaderFont)
                    }
                }
                (noteCapture.error ?: recording.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Your audio is saved before you resume. With an offline transcription pack installed, your words are recognized here on your device.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { haptic(view); capture.save(true) }, enabled = !noteCapture.saving && recording.elapsedMs > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    if (noteCapture.saving) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(8.dp)); Text(if (noteCapture.saving) "Saving your recording" else "Save and resume")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { haptic(view); capture.cancel() }, enabled = !noteCapture.saving) { Text("Cancel recording") }
                    TextButton(onClick = { haptic(view); capture.save(false) }, enabled = !noteCapture.saving && recording.elapsedMs > 0) { Text("Save without resuming") }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        libraryAction?.let { action ->
            if (action.first in setOf("rename", "delete")) AlertDialog(
                onDismissRequest = { if (!actionSaving) libraryAction = null },
                title = { Text(if (action.first == "rename") "Rename your document" else "Delete this document?") },
                text = { if (action.first == "rename") OutlinedTextField(renameText, { renameText = it }, enabled = !actionSaving, label = { Text("Title") }) else Text("This removes the imported PDF, its thoughts, bookmarks and listening position from this device. Export first to keep a copy.") },
                confirmButton = { TextButton(enabled = !actionSaving && !playback.capturing && (action.first != "rename" || renameText.isNotBlank()), onClick = {
                    actionSaving = true
                    scope.launch {
                        try {
                            if (action.first == "rename") { store.rename(action.second.id, renameText); playback.updateContents(store.contents(action.second.id)) }
                            else {
                                preparation.cancel(action.second.id); playback.stop(action.second.id)
                                val transcriber = org.koin.core.context.GlobalContext.get().get<TranscriptionCoordinator>()
                                library.notes.filter { it.documentId == action.second.id }.forEach { transcriber.cancel(it.id) }
                                files.clean(store.deleteDocument(action.second.id))
                                if ((backStack.lastOrNull() as? DocumentKey)?.id == action.second.id) while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            }
                            libraryAction = null; haptic(view, true)
                        } catch (failure: Exception) { message = failure.message ?: "This change could not be saved." }
                        finally { actionSaving = false }
                    }
                }) { Text(if (actionSaving) "Saving…" else if (action.first == "rename") "Save title" else "Delete") } },
                dismissButton = { TextButton(enabled = !actionSaving, onClick = { libraryAction = null }) { Text("Cancel") } },
            )
        }

        if (typedNote) AlertDialog(
            onDismissRequest = { if (!typedSaving) { typedNote = false; playback.endCapture() } },
            title = { Text("Write your thought") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your thought stays attached to this frozen passage.", style = MaterialTheme.typography.bodySmall)
                    Text("Attached to page ${typedPage + 1}", style = MaterialTheme.typography.labelMedium)
                    if (typedQuote.isNotBlank()) Text(typedQuote, fontFamily = ReaderFont, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(typedText, { typedText = it }, enabled = !typedSaving, placeholder = { Text("What stood out?") }, minLines = 4)
                    typedError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val documentId = typedDocumentId
                    val passageId = typedPassageId
                    typedSaving = true
                    if (documentId != null && passageId != null) scope.launch {
                        try {
                            val id = java.util.UUID.randomUUID().toString()
                            store.saveText(id, documentId, passageId, typedText, typedOffset)
                            typedText = ""; typedNote = false; playback.endCapture(); haptic(view, true)
                        } catch (failure: Exception) { typedError = "Your thought could not be saved. Please retry." }
                        finally { typedSaving = false }
                    }
                    else typedSaving = false
                }, enabled = !typedSaving && typedText.isNotBlank()) { Text(if (typedSaving) "Saving…" else "Save thought") }
            },
            dismissButton = { TextButton(enabled = !typedSaving, onClick = { typedNote = false; playback.endCapture() }) { Text("Cancel") } },
        )
        BrandEntrance()
    }
}

@Composable
private fun LibraryPane(documents: List<LibraryDocument>, audio: PlaybackSnapshot, reducedMotion: Boolean, importing: Boolean, import: () -> Unit, open: (String) -> Unit, action: (String, LibraryDocument) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Label("YOUR LISTENING LIBRARY")
            Spacer(Modifier.height(8.dp))
            Text("A little more room\nfor a good book.", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Text("Bring your PDFs. Find your pace. Keep the thoughts that matter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Box(Modifier.fillMaxWidth().heightIn(min = 180.dp).clip(RoundedCornerShape(28.dp))) {
                ListeningAtmosphere(Modifier.matchParentSize(), audio.playing, reducedMotion)
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (audio.documentId != null) "PICK UP WHERE YOU LEFT OFF" else "YOUR NEXT LISTEN", color = Color(0xFFFFE3CC), style = MaterialTheme.typography.labelSmall)
                    Text(if (audio.documentId != null) audio.title else "Let the page\ncome to you.", color = Color.White, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    FilledTonalButton(onClick = { audio.documentId?.let(open) ?: import() }, enabled = !importing) {
                        Icon(if (audio.documentId == null) Icons.Default.Add else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp)); Text(if (audio.documentId == null) "Import a PDF" else "Return to your passage")
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("On your shelf", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.weight(1f))
                Text("${documents.size} ${if (documents.size == 1) "document" else "documents"}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (documents.isEmpty()) item {
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.AutoStories, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Your library starts with one PDF", style = MaterialTheme.typography.titleMedium)
                    Text("A book, an essay, a paper you have been meaning to get to. Your files stay on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = import, enabled = !importing) { Text("Choose your first document") }
                }
            }
        }
        items(documents, key = { it.id }) { document ->
            Surface(onClick = { open(document.id) }, shape = RoundedCornerShape(24.dp), color = if (audio.documentId == document.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookMark(document.title, Modifier.size(56.dp, 72.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(document.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${document.pageCount} pages · ${if (document.extractionStatus == "preparing") "Preparing locally" else if (document.passageCount > 0) "Ready to listen" else "Original pages"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    var menu by remember(document.id) { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Document actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            listOf("Rename", "Export", "Delete").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action(label.lowercase(), document) }) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun EmptyListeningRoom(import: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.widthIn(max = 420.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Default.Headphones, null, Modifier.padding(30.dp).size(52.dp), tint = MaterialTheme.colorScheme.primary) }
            Text("Your listening room", style = MaterialTheme.typography.displaySmall)
            Text("Open a document from your shelf. Your passage and the thoughts you record will live here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = import) { Text("Import a PDF") }
        }
    }
}

@Composable
private fun DocumentPane(id: String, store: ReaderStore, pdf: PdfAdapter, playback: PlaybackCoordinator, notes: List<VoiceNote>, audio: PlaybackSnapshot, reducedMotion: Boolean, back: () -> Unit, record: () -> Unit, write: (ReaderPassage) -> Unit) {
    var document by remember(id) { mutableStateOf<ReaderContents?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var mode by rememberSaveable(id) { mutableStateOf("passage") }
    var following by rememberSaveable(id) { mutableStateOf(true) }
    var page by rememberSaveable(id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val snapshot by store.snapshot.collectAsStateWithLifecycle()
    val metadata = snapshot.documents.firstOrNull { it.id == id }
    val preparation = koinInject<DocumentPreparation>()
    val progress by preparation.progress.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showPronunciation by remember { mutableStateOf(false) }
    var bookmarks by remember(id) { mutableStateOf<List<Bookmark>>(emptyList()) }
    LaunchedEffect(id) { bookmarks = store.bookmarks(id) }
    LaunchedEffect(id, metadata, progress[id]?.running) { runCatching { store.contents(id) }.onSuccess { document = it }.onFailure { error = "This document could not be opened." } }
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(audio.passageId, following, document) {
        if (following && audio.documentId == id && !audio.preview) {
            page = audio.pageIndex
            val index = document?.passages?.indexOfFirst { it.id == audio.passageId } ?: -1
            if (index >= 0 && mode == "passage") list.animateScrollToItem(index + 1)
        }
    }
    val contents = document
    if (contents == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (error == null) CircularProgressIndicator() else Text(error!!) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 850.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 22.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { haptic(view); back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library") }
                    Column(Modifier.weight(1f)) { Label("THE LISTENING ROOM"); Text(contents.document.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    IconButton(onClick = { showSearch = true; haptic(view) }) { Icon(Icons.Default.Search, "Search document") }
                    IconButton(onClick = { showContents = true; haptic(view) }) { Icon(Icons.Default.List, "Pages and bookmarks") }
                    if (contents.passages.isNotEmpty() && audio.documentId != id) FilledTonalIconButton(onClick = {
                        val saved = store.snapshot.value.positions.firstOrNull { it.documentId == id }
                        val index = contents.passages.indexOfFirst { it.id == saved?.passageId }.coerceAtLeast(0)
                        playback.listen(contents, index, saved?.offsetMillis ?: 0); haptic(view, true)
                    }) { Icon(Icons.Default.PlayArrow, "Start listening") }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(mode == "passage", { mode = "passage"; haptic(view) }, { Text("Passages") })
                    FilterChip(showPronunciation, { showPronunciation = true; haptic(view) }, { Text("Pronunciation") })
                    FilterChip(mode == "page", { mode = "page"; haptic(view) }, { Text("Original PDF") })
                    if (!wide) FilterChip(mode == "notes", { mode = "notes"; haptic(view) }, { Text("Thoughts (${notes.size})") })
                    if (!following && audio.documentId == id && !audio.preview) TextButton(onClick = { following = true; haptic(view) }) { Text("Follow") }
                }
                progress[id]?.let { prepared ->
                    if (prepared.running || prepared.error != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (prepared.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(prepared.error ?: "Preparing page ${prepared.completedPages + 1} of ${prepared.totalPages} locally", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (!prepared.running) TextButton(onClick = { preparation.prepare(id) }) { Text("Retry") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (mode) {
                    "page" -> OriginalPage(contents, page, pdf, audio.passageId.takeIf { audio.documentId == id }, { page = it; following = false }, Modifier.weight(1f))
                    "notes" -> NotesPane(notes, store, { note ->
                        val index = contents.passages.indexOfFirst { it.id == note.passageId }
                        if (index >= 0) { mode = "passage"; following = true; playback.listen(contents, index) }
                    }, Modifier.weight(1f))
                    else -> {
                        if (contents.passages.isEmpty()) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text(if (contents.document.extractionStatus == "preparing") "Preparing your first passage" else "The original is ready.", style = MaterialTheme.typography.headlineMedium)
                                Text("Listening becomes available as pages are prepared. Empty pages remain available in the original PDF.")
                                OutlinedButton(onClick = { mode = "page" }) { Text("View the original PDF") }
                            }
                        } else LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("One passage at a time", fontWeight = FontWeight.SemiBold)
                                        Text("Tap a passage to listen there. Highlights and thoughts use passage-level source anchors.", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            itemsIndexed(contents.passages, key = { _, passage -> passage.id }) { index, passage ->
                                val active = audio.documentId == id && audio.passageId == passage.id
                                Surface(onClick = { haptic(view); playback.listen(contents, index); following = true }, shape = RoundedCornerShape(22.dp), color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                                    Column(Modifier.padding(20.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("PAGE ${passage.pageIndex + 1}${if (active) " · CURRENT" else ""}${if (passage.provenance == "ocr") " · RECOGNIZED" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                            IconButton(onClick = { scope.launch { bookmarks = store.toggleBookmark(java.util.UUID.randomUUID().toString(), id, passage.id, "Page ${passage.pageIndex + 1}"); haptic(view) } }) { Icon(if (bookmarks.any { it.passageId == passage.id }) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Toggle passage bookmark") }
                                            IconButton(onClick = { write(passage) }, enabled = !playback.capturing) { Icon(Icons.Default.EditNote, "Write a thought at this passage") }
                                        }
                                        SelectionContainer { Text(passage.displayText, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 34.sp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (wide) Surface(Modifier.width(310.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp)) {
                    Label("KEEP WHAT MATTERS")
                    Spacer(Modifier.height(8.dp)); Text("Your thoughts", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(18.dp))
                    NotesPane(notes, store, { note ->
                        val index = contents.passages.indexOfFirst { it.id == note.passageId }
                        if (index >= 0) { mode = "passage"; following = true; playback.listen(contents, index) }
                    }, Modifier.weight(1f))
                    if (audio.documentId == id) FilledTonalButton(onClick = record, enabled = audio.durationMillis > 0 && !audio.preparing, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Record a thought") }
                }
            }
        }
    }
    if (showPronunciation) PronunciationDialog(contents, { document = it }, { showPronunciation = false })
    if (showSearch) SearchSheet(id, { hit ->
        val index = contents.passages.indexOfFirst { it.id == hit.passageId }
        if (index >= 0) { mode = "passage"; following = true; playback.listen(contents, index) }
    }, { showSearch = false })
    if (showContents) AlertDialog(onDismissRequest = { showContents = false }, title = { Text("Find your place") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (bookmarks.isNotEmpty()) item { Text("BOOKMARKS", style = MaterialTheme.typography.labelSmall) }
            items(bookmarks, key = { it.id }) { bookmark ->
                TextButton(onClick = { val index = contents.passages.indexOfFirst { it.id == bookmark.passageId }; if (index >= 0) { playback.listen(contents, index); following = true; mode = "passage" }; showContents = false }) { Icon(Icons.Default.Bookmark, null); Spacer(Modifier.width(8.dp)); Text(bookmark.label) }
            }
            item { Text("PREPARED PAGES", style = MaterialTheme.typography.labelSmall) }
            items(contents.sections, key = { it.id }) { section ->
                TextButton(onClick = { val index = contents.passages.indexOfFirst { it.id == section.passageId }; if (index >= 0) { playback.listen(contents, index); following = true; mode = "passage" }; showContents = false }) { Text(section.title) }
            }
        }
    }, confirmButton = { TextButton(onClick = { showContents = false }) { Text("Close") } })

}

@Composable
private fun OriginalPage(contents: ReaderContents, page: Int, pdf: PdfAdapter, activePassageId: String?, selectPage: (Int) -> Unit, modifier: Modifier) {
    val document = contents.document
    var bitmap by remember(document.id, page) { mutableStateOf<Bitmap?>(null) }
    var error by remember(document.id, page) { mutableStateOf<String?>(null) }
    var renderAttempt by remember(document.id, page) { mutableIntStateOf(0) }
    var scale by remember(document.id, page) { mutableFloatStateOf(1f) }
    var offset by remember(document.id, page) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(document.id, page, renderAttempt) {
        try { bitmap = pdf.render(document.filePath, page, 2400) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "This page could not be rendered." }
    }
    val active = contents.passages.firstOrNull { it.id == activePassageId && it.pageIndex == page }
    val regions = if (active != null) contents.pages.firstOrNull { it.pageIndex == page }?.regions.orEmpty().filter { it.start < active.pageEnd && it.end > active.pageStart } else emptyList()
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selectPage(page - 1) }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Previous page") }
            Text("${page + 1} / ${document.pageCount}", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { scale = (scale + 0.5f).coerceAtMost(5f) }, enabled = scale < 5f) { Icon(Icons.Default.ZoomIn, "Zoom in") }
            IconButton(onClick = { scale = 1f; offset = Offset.Zero }) { Icon(Icons.Default.FitScreen, "Fit original page") }
            IconButton(onClick = { selectPage(page + 1) }, enabled = page + 1 < document.pageCount) { Icon(Icons.Default.ChevronRight, "Next page") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFE8E8E3)), contentAlignment = Alignment.Center) {
            val image = bitmap
            val widthPixels = constraints.maxWidth.toFloat()
            val heightPixels = constraints.maxHeight.toFloat()
            val transform = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                val proposed = offset + pan
                offset = Offset(proposed.x.coerceIn(-widthPixels * (scale - 1) / 2, widthPixels * (scale - 1) / 2), proposed.y.coerceIn(-heightPixels * (scale - 1) / 2, heightPixels * (scale - 1) / 2))
            }
            if (image != null) Box(Modifier.fillMaxSize().transformable(transform).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) {
                Image(image.asImageBitmap(), "Original PDF page ${page + 1}. Pinch to zoom, or use zoom controls. Passages provide accessible text.", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                if (regions.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                    val fit = minOf(size.width / image.width, size.height / image.height)
                    val renderedWidth = image.width * fit
                    val renderedHeight = image.height * fit
                    val left = (size.width - renderedWidth) / 2
                    val top = (size.height - renderedHeight) / 2
                    regions.forEach { region -> drawRect(highlight, Offset(left + region.x * renderedWidth, top + region.y * renderedHeight), Size(region.width * renderedWidth, region.height * renderedHeight)) }
                }
            }
            else if (error != null) Column(Modifier.padding(24.dp)) { Text(error!!); TextButton(onClick = { error = null; renderAttempt++ }) { Text("Retry page") } }
            else CircularProgressIndicator(Modifier.padding(48.dp))
        }
    }
}

@Composable
private fun NotesPane(notes: List<VoiceNote>, store: ReaderStore, jump: (VoiceNote) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<VoiceNote?>(null) }
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val playback = koinInject<PlaybackCoordinator>()
    val transcription = koinInject<TranscriptionCoordinator>()
    val models = koinInject<com.osuji.ereaderapp.models.ModelManager>()
    val ownedFiles = koinInject<OwnedFiles>()
    var deleting by remember { mutableStateOf<VoiceNote?>(null) }
    var deletingBusy by remember { mutableStateOf(false) }
    var deletingError by remember { mutableStateOf<String?>(null) }
    var showDownloads by remember { mutableStateOf(false) }
    val audio by playback.state.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (notes.isEmpty()) item {
            Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.BookmarkBorder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Text("A thought worth keeping", style = MaterialTheme.typography.titleMedium)
                Text("Pause and record as you listen. Every thought keeps its place in the document.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(notes.filter { it.status != "draft" }, key = { it.id }) { note ->
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("PAGE ${note.pageIndex + 1} · ${if (note.durationMillis > 0) clock(note.durationMillis) else "THOUGHT"}", style = MaterialTheme.typography.labelSmall)
                    Text(note.quote, fontFamily = ReaderFont, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(note.editedTranscript ?: note.machineTranscript?.takeIf { it.isNotBlank() } ?: when (note.status) {
                        "interrupted" -> "Recording was interrupted. Reopen the app to recover any captured audio."
                        "transcribing" -> "Recognizing your saved recording on this device…"
                        "pending" -> "Recording saved · waiting for a quiet moment to transcribe"
                        "pendingModel" -> "Recording saved · download offline transcription to recognize your words"
                        "failed" -> "Transcription did not finish. Your recording is safe; retry when you are ready."
                        else -> "Recording saved"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (note.durationMillis > 0 && note.recordingPath.isNotBlank() && java.io.File(note.recordingPath).exists()) IconButton(onClick = {
                            playback.playNote(note)
                        }) { Icon(if (audio.noteId == note.id && audio.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (audio.noteId == note.id && audio.playing) "Pause voice note" else "Play voice note") }
                        TextButton(onClick = { jump(note) }) { Text("Return to passage") }
                        if (note.durationMillis > 0) TextButton(onClick = { if (models.asrDirectory() == null) showDownloads = true else transcription.retry(note.id) }, enabled = note.status != "transcribing") { Text(if (note.status == "failed") "Retry transcript" else "Transcribe") }
                        IconButton(onClick = { deleting = note; deletingError = null }, enabled = !playback.capturing) { Icon(Icons.Default.DeleteOutline, "Delete thought") }
                        IconButton(onClick = { editing = note; text = note.editedTranscript ?: note.machineTranscript.orEmpty(); saveError = null }) { Icon(Icons.Default.Edit, "Write or edit transcript") }
                    }
                }
            }
        }
    }
    if (showDownloads) OfflineSpeechSheet { showDownloads = false }
    deleting?.let { note -> AlertDialog(onDismissRequest = { if (!deletingBusy) deleting = null }, title = { Text("Delete this thought?") }, text = { Column { Text("The recording and its transcript will be removed from this device."); deletingError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = {
        TextButton(enabled = !deletingBusy, onClick = {
            deletingBusy = true
            scope.launch {
                try { transcription.cancel(note.id); if (audio.noteId == note.id) playback.stopAndWait(); ownedFiles.clean(store.deleteNote(note.id)); deleting = null }
                catch (failure: Exception) { deletingError = "Your thought could not be deleted. Retry." }
                finally { deletingBusy = false }
            }
        }) { Text(if (deletingBusy) "Deleting…" else "Delete") }
    }, dismissButton = { TextButton(enabled = !deletingBusy, onClick = { deleting = null }) { Text("Cancel") } }) }

    if (editing != null) AlertDialog(
        onDismissRequest = { if (!saving) editing = null },
        title = { Text("Your words") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, { text = it }, enabled = !saving, minLines = 4, label = { Text("Transcript or written thought") })
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val id = editing!!.id
                saving = true
                scope.launch {
                    runCatching { store.editTranscript(id, text) }
                        .onSuccess { editing = null }
                        .onFailure { saveError = "Your words could not be saved. Please retry." }
                    saving = false
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = { editing = null }) { Text("Cancel") } },
    )
}

@Composable
private fun ListeningDock(audio: PlaybackSnapshot, playback: PlaybackCoordinator, available: Boolean, record: () -> Unit, sleep: () -> Unit) {
    val view = LocalView.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp)) {
            if (audio.error != null) Text(audio.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 600.dp
            Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookMark(audio.title, Modifier.size(36.dp, 46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(audio.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (audio.preparing) "Preparing your passage…" else "Page ${audio.pageIndex + 1} · ${audio.voice}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { haptic(view); playback.setSpeed(if (audio.speed >= 2f) 0.75f else audio.speed + 0.25f) }, enabled = available) { Text("${audio.speed}×") }
                IconButton(onClick = sleep, enabled = available) { Icon(Icons.Default.Bedtime, if (audio.sleepRemainingMillis > 0) "Sleep timer running" else "Set sleep timer") }
                if (!compact) TransportControls(audio, playback, available, record)
            }
            if (compact) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TransportControls(audio, playback, available, record)
            }
            }
            }
            if (audio.durationMillis > 0) {
                Slider(value = audio.positionMillis.toFloat().coerceIn(0f, audio.durationMillis.toFloat()), onValueChange = { playback.seek(it.toLong()) }, valueRange = 0f..audio.durationMillis.toFloat(), enabled = available, modifier = Modifier.fillMaxWidth().height(28.dp))
            }
            if (audio.sleepRemainingMillis > 0) Text("Sleep in ${clock(audio.sleepRemainingMillis)}", style = MaterialTheme.typography.labelSmall)
            if (audio.preview) TextButton(onClick = { playback.returnFromPreview() }) { Text("End voice preview") }
            if (audio.noteId != null) TextButton(onClick = { haptic(view); playback.returnToReading() }) { Text("Return to reading") }
        }
    }
}

@Composable
private fun TransportControls(audio: PlaybackSnapshot, playback: PlaybackCoordinator, available: Boolean, record: () -> Unit) {
    val view = LocalView.current
    IconButton(onClick = { haptic(view); playback.skip(-1) }, enabled = available && audio.noteId == null && !audio.preview && !audio.preparing) { Icon(Icons.Default.SkipPrevious, "Previous passage") }
    FilledIconButton(onClick = { haptic(view, true); playback.toggle() }, enabled = available, modifier = Modifier.size(52.dp)) {
        if (audio.preparing) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
        else Icon(if (audio.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (audio.playing) "Pause" else "Play")
                }
    IconButton(onClick = { haptic(view); playback.skip(1) }, enabled = available && audio.noteId == null && !audio.preview && !audio.preparing) { Icon(Icons.Default.SkipNext, "Next passage") }
    IconButton(onClick = record, enabled = available && audio.noteId == null && !audio.preview && audio.durationMillis > 0 && !audio.preparing) { Icon(Icons.Default.Mic, "Pause and record a thought") }
}

@Composable
private fun BookMark(title: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp)).background(Ember), contentAlignment = Alignment.Center) {
        Text(title.take(1).uppercase(), color = Color(0xFFF0ECD9), fontFamily = ReaderFont, fontSize = 22.sp)
    }
}

@Composable
private fun Label(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }

private fun clock(milliseconds: Long): String = "%d:%02d".format(milliseconds / 60_000, milliseconds / 1000 % 60)
