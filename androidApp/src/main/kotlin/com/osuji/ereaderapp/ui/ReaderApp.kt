package com.osuji.ereaderapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import com.osuji.ereaderapp.R
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
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
import com.osuji.ereaderapp.audio.MediaOutputVolume
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
import com.osuji.ereaderapp.pdf.SourceRegion
import com.osuji.ereaderapp.pdf.PdfPageGeometry
import com.osuji.ereaderapp.pdf.PdfPageCoordinates
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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
    var originalPdfColours by rememberSaveable { mutableStateOf(preferences.getBoolean("originalPdfColours", false)) }
    var typedNote by rememberSaveable { mutableStateOf(false) }
    var typedText by rememberSaveable { mutableStateOf("") }
    var typedDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var typedPassageId by rememberSaveable { mutableStateOf<String?>(null) }
    var typedOffset by rememberSaveable { mutableLongStateOf(0) }
    var typedQuote by rememberSaveable { mutableStateOf("") }
    var typedPage by rememberSaveable { mutableIntStateOf(0) }
    var typedSaving by remember { mutableStateOf(false) }
    var typedError by remember { mutableStateOf<String?>(null) }
    var recordRequestPending by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(recordRequestPending, audio.capturing) {
        if (recordRequestPending && !playback.capturing) recordRequestPending = false
    }
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
            try { check(playback.persist()) { "Your listening position could not be saved. Please retry before exporting." }; archives.export(id, uri); haptic(view, true); message = "Your PDF, thoughts, bookmarks and listening position were exported." }
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
        if (recordRequestPending) {
            recordRequestPending = false
            if (playback.capturing && playback.state.value.documentId == typedDocumentId && playback.state.value.passageId == typedPassageId) {
                if (allowed) { haptic(view, true); capture.begin() }
                else { typedNote = true; typedError = null; message = null }
            }
        }
    }
    fun recordThought(expectedDocumentId: String? = null, expectedPassageId: String? = null, expectedSourceRevision: String? = null): Boolean {
        if (recordRequestPending || typedNote || playback.capturing || capture.state.value.draft != null) return false
        val current = playback.state.value
        if (current.preview || current.noteId != null || current.preparing || current.durationMillis <= 0) return false
        val frozen = playback.beginCapture(expectedDocumentId, expectedPassageId, expectedSourceRevision) ?: return false
        recordRequestPending = true
        typedDocumentId = frozen.documentId
        typedPassageId = frozen.passageId
        typedOffset = frozen.offsetMillis
        typedPage = playback.state.value.pageIndex
        typedQuote = ""
        scope.launch {
            runCatching { store.contents(frozen.documentId) }.onSuccess { contents ->
                if (typedDocumentId == frozen.documentId && typedPassageId == frozen.passageId) {
                    typedQuote = contents.passages.firstOrNull { it.id == frozen.passageId }?.sourceText.orEmpty()
                }
            }
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recordRequestPending = false
            haptic(view, true)
            capture.begin()
        } else {
            try { microphone.launch(Manifest.permission.RECORD_AUDIO) }
            catch (failure: Exception) { recordRequestPending = false; typedNote = true; typedError = null }
        }
        return true
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
            bottomBar = {
                if (audio.documentId != null) ListeningDock(audio, library.documents.firstOrNull { it.id == audio.documentId }, pdf, playback, !playback.capturing, { recordThought() }, { showSleep = true })
            },
        ) { padding ->
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize().padding(padding),
                onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                sceneStrategies = listOf(sceneStrategy),
                entryProvider = entryProvider {
                    entry<LibraryKey>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { EmptyReader { importPicker.launch(arrayOf("application/pdf")) } })) {
                        LibraryPane(
                            documents = library.documents,
                            pdf = pdf,
                            audio = audio,
                            importing = importing,
                            import = { importPicker.launch(arrayOf("application/pdf")); haptic(view) },
                            search = { showSearch = true; haptic(view) },
                            voices = { showVoices = true; haptic(view) },
                            settings = { showSettings = true; haptic(view) },
                            open = ::openDocument,
                            action = { action, document -> libraryAction = action to document; renameText = document.title },
                        )
                    }
                    entry<DocumentKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                        DocumentPane(key.id, store, pdf, playback, library.notes.filter { it.documentId == key.id }, audio, originalPdfColours, { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }, ::recordThought, { passage -> writeThought(key.id, passage) })
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
                    Text("Pinch to zoom and drag to pan. Swipe vertically between fitted pages. Accessible page text follows your system size.")
                    Text("Appearance", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("system", "light", "dark").forEach { mode -> FilterChip(appearance == mode, { appearance = mode; preferences.edit().putString("appearance", mode).apply(); haptic(view) }, { Text(mode.replaceFirstChar { it.uppercase() }) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Original colours")
                            Text("Keep the PDF's paper and colours unchanged.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(originalPdfColours, { originalPdfColours = it; preferences.edit().putBoolean("originalPdfColours", it).apply(); haptic(view) })
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
private fun LibraryPane(
    documents: List<LibraryDocument>,
    pdf: PdfAdapter,
    audio: PlaybackSnapshot,
    importing: Boolean,
    import: () -> Unit,
    search: () -> Unit,
    voices: () -> Unit,
    settings: () -> Unit,
    open: (String) -> Unit,
    action: (String, LibraryDocument) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_rhapsodos), null, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text("Rhapsodos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = search, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Search")
                    }
                    FilledTonalButton(onClick = voices, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Headphones, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Offline voices")
                    }
                    FilledTonalButton(onClick = settings, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Settings")
                    }
                    Button(onClick = import, enabled = !importing, modifier = Modifier.heightIn(min = 48.dp)) {
                        if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (importing) "Importing" else "Import")
                    }
                }
            }
        }
        item {
            Label("YOUR LISTENING LIBRARY")
            Spacer(Modifier.height(8.dp))
            Text("A little more room\nfor a good book.", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
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
                    Text("A book, an essay, a paper you have been meaning to get to.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = import, enabled = !importing) { Text("Choose your first document") }
                }
            }
        }
        items(documents, key = { it.id }) { document ->
            Surface(onClick = { open(document.id) }, shape = RoundedCornerShape(24.dp), color = if (audio.documentId == document.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    PdfCover(document, pdf, Modifier.size(56.dp, 72.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(document.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${document.pageCount} pages · ${if (document.extractionStatus == "preparing") "Preparing locally" else if (document.passageCount > 0) "Ready to listen" else "Original pages"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        document.listeningProgress?.let { progress ->
                            val fraction = progress.coerceIn(0.0, 1.0)
                            LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Listening progress" })
                            Text(if (fraction == 1.0) "Finished" else if (fraction == 0.0) "Not started" else "${(fraction * 100).toInt()}% listened", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
private fun EmptyReader(import: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.widthIn(max = 420.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Default.Headphones, null, Modifier.padding(30.dp).size(52.dp), tint = MaterialTheme.colorScheme.primary) }
            Text("Open a document from your shelf.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = import) { Text("Import a PDF") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentPane(id: String, store: ReaderStore, pdf: PdfAdapter, playback: PlaybackCoordinator, notes: List<VoiceNote>, audio: PlaybackSnapshot, originalColours: Boolean, back: () -> Unit, record: (String, String, String) -> Boolean, write: (ReaderPassage) -> Unit) {
    var document by remember(id) { mutableStateOf<ReaderContents?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var following by rememberSaveable(id) { mutableStateOf(true) }
    var page by rememberSaveable(id) { mutableIntStateOf(0) }
    var restored by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val snapshot by store.snapshot.collectAsStateWithLifecycle()
    val metadata = snapshot.documents.firstOrNull { it.id == id }
    val preparation = koinInject<DocumentPreparation>()
    val progress by preparation.progress.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showPronunciation by remember { mutableStateOf(false) }
    var showThoughts by rememberSaveable(id) { mutableStateOf(false) }
    var showPassageTools by remember { mutableStateOf(false) }
    var bookmarks by remember(id) { mutableStateOf<List<Bookmark>>(emptyList()) }
    LaunchedEffect(id) { bookmarks = store.bookmarks(id) }
    LaunchedEffect(id, metadata?.title, metadata?.sourceRevision, metadata?.passageCount, metadata?.extractionStatus, progress[id]?.running) {
        try { document = store.contents(id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "This document could not be opened." }
    }
    LaunchedEffect(audio.passageId, audio.pageIndex, audio.documentId, audio.preview, following) {
        if (following && audio.documentId == id && !audio.preview) page = audio.pageIndex
    }
    val contents = document
    LaunchedEffect(id, contents?.passages?.isNotEmpty()) {
        if (contents != null && !restored) {
            val current = playback.state.value
            val saved = store.snapshot.value.positions.firstOrNull { it.documentId == id }
            page = if (current.documentId == id && !current.preview) current.pageIndex else contents.passages.firstOrNull { it.id == saved?.passageId }?.pageIndex ?: 0
            playback.openSaved(contents)
            restored = contents.passages.isNotEmpty() || contents.document.extractionStatus != "preparing"
        }
    }
    if (contents == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (error == null) CircularProgressIndicator() else Text(error!!) }
        return
    }
    val active = contents.passages.firstOrNull { it.id == audio.passageId && audio.documentId == id && !audio.preview && audio.noteId == null }
    val currentAvailable = active != null && !audio.capturing
    fun returnToPassage(passageId: String) {
        val index = contents.passages.indexOfFirst { it.id == passageId }
        if (index >= 0 && !playback.capturing) { following = true; playback.listen(contents, index) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 850.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 22.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { haptic(view); back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library") }
                    Text(contents.document.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showSearch = true; haptic(view) }) { Icon(Icons.Default.Search, "Search document") }
                    IconButton(onClick = { showContents = true; haptic(view) }) { Icon(Icons.Default.List, "Pages and bookmarks") }
                    Box {
                        IconButton(onClick = { showPassageTools = true; haptic(view) }) { Icon(Icons.Default.MoreVert, "Current passage actions") }
                        DropdownMenu(expanded = showPassageTools, onDismissRequest = { showPassageTools = false }) {
                            DropdownMenuItem(text = { Text(if (bookmarks.any { it.passageId == active?.id }) "Remove current bookmark" else "Bookmark current passage") }, enabled = currentAvailable, onClick = {
                                showPassageTools = false
                                active?.let { passage -> scope.launch { bookmarks = store.toggleBookmark(java.util.UUID.randomUUID().toString(), id, passage.id, "Page ${passage.pageIndex + 1}"); haptic(view) } }
                            })
                            DropdownMenuItem(text = { Text("Write a thought") }, enabled = currentAvailable, onClick = { showPassageTools = false; active?.let(write) })
                            DropdownMenuItem(text = { Text("Pronunciation") }, onClick = { showPassageTools = false; showPronunciation = true; haptic(view) })
                        }
                    }
                    if (contents.passages.isNotEmpty() && audio.documentId != id) FilledTonalIconButton(onClick = {
                        val saved = store.snapshot.value.positions.firstOrNull { it.documentId == id }
                        val index = contents.passages.indexOfFirst { it.id == saved?.passageId }.coerceAtLeast(0)
                        following = true
                        playback.listen(contents, index, saved?.offsetMillis ?: 0, passageFraction = saved?.passageFraction ?: 0.0); haptic(view, true)
                    }, enabled = !audio.capturing) { Icon(Icons.Default.PlayArrow, "Start listening") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!wide) TextButton(onClick = { showThoughts = true; haptic(view) }) { Icon(Icons.Default.EditNote, null); Spacer(Modifier.width(6.dp)); Text("Thoughts (${notes.count { it.status != "draft" }})") }
                    Spacer(Modifier.weight(1f))
                    if (!following && audio.documentId == id && !audio.preview) TextButton(onClick = { following = true; haptic(view) }) { Text("Follow narration") }
                }
                progress[id]?.let { prepared ->
                    if (prepared.running || prepared.error != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (prepared.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(prepared.error ?: "Preparing page ${prepared.completedPages + 1} of ${prepared.totalPages} locally", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (!prepared.running) TextButton(onClick = { preparation.prepare(id) }) { Text("Retry") }
                    }
                }
                OriginalPages(contents, page.coerceIn(0, contents.document.pageCount - 1), pdf, originalColours, active, currentAvailable && audio.durationMillis > 0 && !audio.preparing,
                    { page = it; following = false }, { following = false },
                    { passageId, revision -> record(id, passageId, revision) }, { document = it; playback.updateContents(it) }, Modifier.weight(1f))
            }
            if (wide) Surface(Modifier.width(310.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp)) {
                    Label("KEEP WHAT MATTERS")
                    Spacer(Modifier.height(8.dp)); Text("Your thoughts", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(18.dp))
                    NotesPane(notes, store, { note -> returnToPassage(note.passageId) }, Modifier.weight(1f))
                }
            }
        }
    }
    if (showThoughts) ModalBottomSheet(onDismissRequest = { showThoughts = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).navigationBarsPadding().padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your thoughts", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { showThoughts = false }) { Text("Close") }
            }
            NotesPane(notes, store, { note -> returnToPassage(note.passageId); showThoughts = false }, Modifier.weight(1f))
        }
    }
    if (showPronunciation) PronunciationDialog(contents, { document = it }, { showPronunciation = false })
    if (showSearch) SearchSheet(id, { hit -> returnToPassage(hit.passageId) }, { showSearch = false })
    if (showContents) AlertDialog(onDismissRequest = { showContents = false }, title = { Text("Find your place") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (bookmarks.isNotEmpty()) item { Text("BOOKMARKS", style = MaterialTheme.typography.labelSmall) }
            items(bookmarks, key = { it.id }) { bookmark ->
                TextButton(onClick = { returnToPassage(bookmark.passageId); showContents = false }) { Icon(Icons.Default.Bookmark, null); Spacer(Modifier.width(8.dp)); Text(bookmark.label) }
            }
            if (contents.sections.isNotEmpty()) item { Text("CHAPTERS", style = MaterialTheme.typography.labelSmall) }
            items(contents.sections, key = { it.id }) { section ->
                TextButton(onClick = { returnToPassage(section.passageId); showContents = false }) { Text(section.title) }
            }
            item { Text("PAGES", style = MaterialTheme.typography.labelSmall) }
            items(contents.document.pageCount) { index ->
                TextButton(onClick = { page = index; following = false; showContents = false }) { Text("Page ${index + 1}") }
            }
        }
    }, confirmButton = { TextButton(onClick = { showContents = false }) { Text("Close") } })
}

internal class PdfPagePlacement(viewportWidth: Float, viewportHeight: Float, imageWidth: Float, imageHeight: Float, scale: Float, panX: Float, panY: Float) {
    private val viewport = Rect(0f, 0f, viewportWidth, viewportHeight)
    private val fit = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val width = imageWidth * fit * scale
    val height = imageHeight * fit * scale
    val left = (viewportWidth - width) / 2 + panX
    val top = (viewportHeight - height) / 2 + panY
    fun rectangle(region: SourceRegion) = Rect(left + region.x * width, top + region.y * height, left + (region.x + region.width) * width, top + (region.y + region.height) * height)
    fun contains(x: Float, y: Float, regions: List<SourceRegion>): Boolean {
        val point = Offset(x, y)
        return viewport.contains(point) && regions.any { region -> region.width > 0 && region.height > 0 && rectangle(region).contains(point) }
    }
    fun visible(regions: List<SourceRegion>): Boolean = regions.any { it.width > 0 && it.height > 0 && rectangle(it).overlaps(viewport) }
}

private data class DrawnPdfHighlight(val passageId: String, val revision: String, val placement: PdfPagePlacement, val regions: List<SourceRegion>)
private class PdfHighlightInteraction { var drawn: DrawnPdfHighlight? = null }

internal class PdfHighlightTap(private val startX: Float, private val startY: Float, private val startedAt: Long, private val slop: Float, private val maximumDuration: Long) {
    private var accepted = true
    fun update(x: Float, y: Float, uptime: Long, pointerCount: Int, consumed: Boolean): Boolean {
        val distance = Offset(x - startX, y - startY).getDistance()
        accepted = accepted && pointerCount == 1 && !consumed && uptime - startedAt in 0 until maximumDuration && distance <= slop
        return accepted
    }
}

@Composable
private fun OriginalPages(contents: ReaderContents, page: Int, pdf: PdfAdapter, originalColours: Boolean, activePassage: ReaderPassage?, recordAvailable: Boolean, selectPage: (Int) -> Unit, browse: () -> Unit, record: (String, String) -> Boolean, geometryUpdated: (ReaderContents) -> Unit, modifier: Modifier) {
    val pager = rememberPagerState(initialPage = page) { contents.document.pageCount }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val latestBrowse by rememberUpdatedState(browse)
    val latestSelect by rememberUpdatedState(selectPage)
    val latestPage by rememberUpdatedState(page)
    var zoomedPage by remember(contents.document.id) { mutableStateOf<Int?>(null) }
    var programmaticScroll by remember(pager) { mutableStateOf(false) }
    LaunchedEffect(page, pager) {
        if (pager.currentPage != page) {
            programmaticScroll = true
            try { pager.scrollToPage(page) }
            finally { programmaticScroll = false }
        }
    }
    LaunchedEffect(pager) {
        var manual = false
        var previousSettled = pager.settledPage
        snapshotFlow { programmaticScroll to Triple(dragged, pager.isScrollInProgress, pager.settledPage) }.collect { (programmatic, state) ->
            val (dragging, scrolling, settled) = state
            if (dragging || scrolling && !programmatic) { manual = true; latestBrowse() }
            if (!scrolling) {
                val changed = settled != previousSettled
                previousSettled = settled
                if (!programmatic) {
                    if ((manual || changed) && settled != latestPage) { latestBrowse(); latestSelect(settled) }
                    manual = false
                }
            }
        }
    }
    VerticalPager(pager, modifier, userScrollEnabled = zoomedPage != pager.currentPage, beyondViewportPageCount = 0, key = { it }) { index ->
        val visible = index == pager.currentPage || pager.layoutInfo.visiblePagesInfo.any { it.index == index }
        OriginalPage(contents, index, pdf, originalColours, visible, activePassage, recordAvailable, selectPage, browse,
            { zoomed -> if (zoomed) zoomedPage = index else if (zoomedPage == index) zoomedPage = null }, record, geometryUpdated, Modifier.fillMaxSize())
    }
}

@Composable
private fun OriginalPage(contents: ReaderContents, page: Int, pdf: PdfAdapter, originalColours: Boolean, visible: Boolean, activePassage: ReaderPassage?, recordAvailable: Boolean, selectPage: (Int) -> Unit, browse: () -> Unit, zoomed: (Boolean) -> Unit, record: (String, String) -> Boolean, geometryUpdated: (ReaderContents) -> Unit, modifier: Modifier) {
    val document = contents.document
    var bitmap by remember(document.id, page) { mutableStateOf<Bitmap?>(null) }
    var error by remember(document.id, page) { mutableStateOf<String?>(null) }
    var renderAttempt by remember(document.id, page) { mutableIntStateOf(0) }
    var geometryAttempt by remember(document.id, page) { mutableIntStateOf(0) }
    var geometry by remember(document.id, page) { mutableStateOf<PdfPageGeometry?>(null) }
    var geometryError by remember(document.id, page) { mutableStateOf<String?>(null) }
    var scale by remember(document.id, page) { mutableFloatStateOf(1f) }
    var offset by remember(document.id, page) { mutableStateOf(Offset.Zero) }
    val interaction = remember(document.id, page) { PdfHighlightInteraction() }
    val latestRecord by rememberUpdatedState(record)
    val latestAvailable by rememberUpdatedState(recordAvailable)
    val density = LocalDensity.current
    val preparation = koinInject<DocumentPreparation>()
    val preparedPage = contents.pages.firstOrNull { it.pageIndex == page }
    val paper = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground
    LaunchedEffect(document.id, page, renderAttempt, originalColours, paper, ink, visible) {
        error = null
        if (!visible) { bitmap = null; return@LaunchedEffect }
        var raw: Bitmap? = null
        var displayed: Bitmap? = null
        var delivered = false
        try {
            raw = pdf.render(document.filePath, page, 2400)
            displayed = if (originalColours) raw else PdfDisplayColours.recolour(raw, paper.toArgb(), ink.toArgb())
            bitmap = displayed
            delivered = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "This page could not be rendered." }
        finally {
            if (raw !== displayed || !delivered) raw?.recycle()
            if (!delivered && displayed !== raw) displayed?.recycle()
        }
    }
    LaunchedEffect(scale) { zoomed(scale > 1f) }
    LaunchedEffect(document.id, page, preparedPage, geometryAttempt, visible) {
        geometryError = null
        if (!visible || preparedPage == null || preparedPage.text.isBlank()) return@LaunchedEffect
        try {
            if (geometry == null) geometry = pdf.pageGeometry(document.filePath, page)
            if (preparedPage.geometrySpace != "cropTopLeftV1" || preparedPage.regions.isEmpty()) geometryUpdated(preparation.repairGeometry(document.id, page))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { geometryError = "This page's highlighting is unavailable. Use the microphone button to record at your listening position." }
    }
    val active = activePassage?.takeIf { it.pageIndex == page }
    val latestActive by rememberUpdatedState(active)
    val displayRegions = if (preparedPage?.geometrySpace == "cropTopLeftV1" && geometry != null) preparedPage.regions.map { PdfPageCoordinates.displayed(it, geometry!!.rotation) } else emptyList()
    val regions = if (active != null) displayRegions.filter { it.start < active.pageEnd && it.end > active.pageStart } else emptyList()
    val accent = if (originalColours) Color(0xFF9F3800) else MaterialTheme.colorScheme.primary
    val highlight = accent.copy(alpha = 0.18f)
    val outline = accent.copy(alpha = 0.85f)
    Column(modifier.fillMaxWidth()) {
        geometryError?.let { unavailable ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(unavailable, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { geometryAttempt++ }) { Text("Retry highlight") }
            }
        }
        if (bitmap != null && error != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(error!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { renderAttempt++ }) { Text("Retry page") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(paper), contentAlignment = Alignment.Center) {
            val image = bitmap
            val widthPixels = constraints.maxWidth.toFloat()
            val heightPixels = constraints.maxHeight.toFloat()
            fun updateTransform(nextScale: Float, proposed: Offset) {
                scale = nextScale.coerceIn(1f, 5f)
                val fit = if (image != null) minOf(widthPixels / image.width, heightPixels / image.height) else 1f
                val horizontal = ((image?.width ?: 0) * fit * scale - widthPixels).coerceAtLeast(0f) / 2
                val vertical = ((image?.height ?: 0) * fit * scale - heightPixels).coerceAtLeast(0f) / 2
                offset = Offset(proposed.x.coerceIn(-horizontal, horizontal), proposed.y.coerceIn(-vertical, vertical))
                browse()
            }
            val transform = rememberTransformableState { zoom, pan, _ -> updateTransform(scale * zoom, offset + pan) }
            if (image != null) {
                val pixels = remember(image) { image.asImageBitmap() }
                val placement = PdfPagePlacement(widthPixels, heightPixels, image.width.toFloat(), image.height.toFloat(), scale, offset.x, offset.y)
                Canvas(Modifier.fillMaxSize().transformable(transform, canPan = { scale > 1f }).pointerInput(document.id, page) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val candidate = interaction.drawn?.takeIf { latestAvailable && !down.isConsumed && it.placement.contains(down.position.x, down.position.y, it.regions) }
                        val tap = PdfHighlightTap(down.position.x, down.position.y, down.uptimeMillis, viewConfiguration.touchSlop, minOf(viewConfiguration.longPressTimeoutMillis, 500L))
                        tap.update(down.position.x, down.position.y, down.uptimeMillis, currentEvent.changes.count { it.pressed || it.previousPressed }, down.isConsumed)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val accepted = tap.update(change.position.x, change.position.y, change.uptimeMillis, event.changes.count { it.pressed || it.previousPressed }, change.isConsumed)
                            if (!change.pressed) {
                                val drawn = interaction.drawn
                                if (accepted && candidate != null && drawn != null && latestAvailable && candidate.passageId == drawn.passageId && candidate.revision == drawn.revision && latestActive?.id == drawn.passageId && drawn.placement.contains(change.position.x, change.position.y, drawn.regions)) {
                                    change.consume()
                                    latestRecord(drawn.passageId, drawn.revision)
                                }
                                break
                            }
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            val finalChange = final.changes.firstOrNull { it.id == down.id } ?: break
                            tap.update(finalChange.position.x, finalChange.position.y, finalChange.uptimeMillis, final.changes.count { it.pressed || it.previousPressed }, finalChange.isConsumed)
                        }
                    }
                }.onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.PageUp -> if (page > 0) { selectPage(page - 1); true } else false
                        Key.PageDown -> if (page + 1 < document.pageCount) { selectPage(page + 1); true } else false
                        Key.Plus, Key.Equals -> if (scale < 5f) { updateTransform(scale + 0.5f, offset); true } else false
                        Key.Minus -> if (scale > 1f) { updateTransform(scale - 0.5f, offset); true } else false
                        Key.Escape -> if (scale > 1f) { updateTransform(1f, Offset.Zero); true } else false
                        else -> false
                    }
                }.focusable().semantics {
                    contentDescription = "PDF page ${page + 1} of ${document.pageCount}. Pinch to zoom and drag to pan. Swipe vertically between fitted pages." + if (displayRegions.isEmpty()) preparedPage?.text?.let { " Prepared page text: $it" }.orEmpty() else ""
                    customActions = buildList {
                        if (page > 0) add(CustomAccessibilityAction("Previous page") { selectPage(page - 1); true })
                        if (page + 1 < document.pageCount) add(CustomAccessibilityAction("Next page") { selectPage(page + 1); true })
                        if (scale < 5f) add(CustomAccessibilityAction("Zoom in") { updateTransform(scale + 0.5f, offset); true })
                        if (scale > 1f) {
                            add(CustomAccessibilityAction("Zoom out") { updateTransform(scale - 0.5f, offset); true })
                            add(CustomAccessibilityAction("Reset zoom") { updateTransform(1f, Offset.Zero); true })
                        }
                    }
                }) {
                    val drawnPlacement = PdfPagePlacement(size.width, size.height, image.width.toFloat(), image.height.toFloat(), scale, offset.x, offset.y)
                    withTransform({ translate(drawnPlacement.left, drawnPlacement.top); scale(drawnPlacement.width / image.width, drawnPlacement.height / image.height, pivot = Offset.Zero) }) { drawImage(pixels) }
                    regions.forEach { region ->
                        val rect = drawnPlacement.rectangle(region)
                        drawRect(highlight, rect.topLeft, rect.size)
                        drawRect(outline, rect.topLeft, rect.size, style = Stroke(1.dp.toPx()))
                    }
                    interaction.drawn = active?.takeIf { regions.isNotEmpty() }?.let { DrawnPdfHighlight(it.id, document.sourceRevision, drawnPlacement, regions) }
                }
                contents.passages.filter { it.pageIndex == page }.forEach { passage ->
                    val passageRegions = displayRegions.filter { it.start < passage.pageEnd && it.end > passage.pageStart }
                    val rectangles = passageRegions.map(placement::rectangle).filter { it.overlaps(Rect(0f, 0f, widthPixels, heightPixels)) }
                    if (rectangles.isNotEmpty()) {
                        val bounds = Rect(rectangles.minOf { it.left }.coerceAtLeast(0f), rectangles.minOf { it.top }.coerceAtLeast(0f), rectangles.maxOf { it.right }.coerceAtMost(widthPixels), rectangles.maxOf { it.bottom }.coerceAtMost(heightPixels))
                        Box(Modifier.align(Alignment.TopStart).offset { IntOffset(bounds.left.toInt(), bounds.top.toInt()) }.size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }).semantics {
                            contentDescription = if (passage.id == active?.id) "Current narration passage. ${passage.sourceText}" else passage.sourceText
                            if (recordAvailable && passage.id == active?.id) customActions = listOf(CustomAccessibilityAction("Pause and record a thought at the current passage") {
                                val drawn = interaction.drawn
                                drawn != null && latestAvailable && latestActive?.id == drawn.passageId && drawn.placement.visible(drawn.regions) && latestRecord(drawn.passageId, drawn.revision)
                            })
                        })
                    }
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
private fun ListeningDock(audio: PlaybackSnapshot, document: LibraryDocument?, pdf: PdfAdapter, playback: PlaybackCoordinator, available: Boolean, record: () -> Unit, sleep: () -> Unit) {
    val view = LocalView.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp)) {
            if (audio.error != null) Text(audio.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 600.dp
            Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PdfCover(document, pdf, Modifier.size(36.dp, 46.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(audio.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (audio.preparing) Text("Preparing your passage…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { haptic(view); playback.setSpeed(if (audio.speed >= 2f) 0.75f else audio.speed + 0.25f) }, enabled = available) { Text("${audio.speed}×") }
                MediaVolumeControl()
                IconButton(onClick = sleep, enabled = available) { Icon(Icons.Default.Bedtime, if (audio.sleepRemainingMillis > 0) "Sleep timer running" else "Set sleep timer") }
                if (!compact) TransportControls(audio, playback, available, record)
            }
            if (compact) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TransportControls(audio, playback, available, record)
            }
            }
            }
            SourceProgressMeter(audio, playback, available)
            if (audio.sleepRemainingMillis > 0) Text("Sleep in ${clock(audio.sleepRemainingMillis)}", style = MaterialTheme.typography.labelSmall)
            if (audio.preview) TextButton(onClick = { playback.returnFromPreview() }) { Text("End voice preview") }
            if (audio.noteId != null) TextButton(onClick = { haptic(view); playback.returnToReading() }) { Text("Return to reading") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaVolumeControl() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val view = LocalView.current
    val output = remember(context) { MediaOutputVolume(context) }
    val volume by output.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val slider = remember(volume.minimum, volume.maximum) { SliderState(value = volume.current.toFloat(), steps = (volume.maximum - volume.minimum - 1).coerceAtLeast(0), trackRange = volume.minimum.toFloat()..volume.maximum.coerceAtLeast(volume.minimum + 1).toFloat()) }
    LaunchedEffect(output, lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { output.observe() } }
    LaunchedEffect(volume.current) { slider.value = volume.current.toFloat() }
    Box {
        IconButton(onClick = { output.refresh(); expanded = true; haptic(view) }) {
            Icon(if (volume.current <= volume.minimum) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Media volume")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.widthIn(min = 200.dp, max = 320.dp).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Media volume", style = MaterialTheme.typography.titleSmall)
                Slider(
                    state = slider,
                    onValueChange = { value -> output.setIndex(kotlin.math.round(value).toInt()); slider.value = output.state.value.current.toFloat() },
                    enabled = volume.adjustable,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Media output volume"; stateDescription = "${(volume.fraction * 100).toInt()} percent" },
                )
                Text(when { !volume.available -> "Volume unavailable"; volume.fixed || !volume.adjustable -> "Adjust volume on your output device."; else -> "${(volume.fraction * 100).toInt()}%" }, style = MaterialTheme.typography.labelSmall)
                volume.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceProgressMeter(audio: PlaybackSnapshot, playback: PlaybackCoordinator, available: Boolean) {
    val documentId = audio.documentId
    val revision = audio.sourceRevision
    val progress = audio.sourceProgress
    var scrub by remember(documentId, revision, audio.preview, audio.noteId, available) { mutableStateOf<Float?>(null) }
    val slider = remember(documentId, revision, audio.preview, audio.noteId, available) { SliderState(value = progress?.toFloat() ?: 0f) }
    val enabled = available && documentId != null && revision != null && progress != null && !audio.preview && audio.noteId == null
    LaunchedEffect(progress, scrub) { if (scrub == null) slider.value = progress?.toFloat() ?: 0f }
    if (progress == null) {
        Text("Preparing document progress…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val shown = (scrub ?: progress.toFloat()).coerceIn(0f, 1f)
        Slider(
            state = slider,
            onValueChange = { scrub = it; slider.value = it },
            onValueChangeFinished = {
                val requested = scrub
                scrub = null
                if (enabled && requested != null) playback.seekSource(documentId, revision, requested.toDouble())
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Document progress"; stateDescription = "${(shown * 100).toInt()} percent of document" },
        )
        Text("${(shown * 100).toInt()}% of document", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun PdfCover(document: LibraryDocument?, pdf: PdfAdapter, modifier: Modifier) {
    var bitmap by remember(document?.id, document?.filePath, document?.sourceRevision) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(document?.id, document?.filePath, document?.sourceRevision, pdf) {
        bitmap = null
        if (document != null) {
            try { bitmap = pdf.cover(document.filePath, document.sourceRevision) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { bitmap = null }
        }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val cover = bitmap
        if (cover != null) Image(cover.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        else Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Label(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }

private fun clock(milliseconds: Long): String = "%d:%02d".format(milliseconds / 60_000, milliseconds / 1000 % 60)
