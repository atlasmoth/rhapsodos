package com.osuji.ereaderapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
    val preferences = remember { context.getSharedPreferences("presentation", android.content.Context.MODE_PRIVATE) }
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
                store.import(imported)
                openDocument(imported.id)
                haptic(view, true)
                imported.extractionMessage?.let { message = it }
            } catch (failure: Exception) {
                message = failure.message ?: "This PDF could not be imported. Please try another file."
            } finally { importing = false }
        }
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

    ReaderTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactHeader = maxWidth < 500.dp
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoStories, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("eReader", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { showVoices = true; haptic(view) }) { Icon(Icons.Default.Headphones, "Offline voices") }
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
                }
            },
            bottomBar = {
                if (audio.documentId != null) ListeningDock(audio, playback, !playback.capturing, ::recordThought)
            },
        ) { padding ->
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize().padding(padding),
                onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                sceneStrategies = listOf(sceneStrategy),
                entryProvider = entryProvider {
                    entry<LibraryKey>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { EmptyListeningRoom { importPicker.launch(arrayOf("application/pdf")) } })) {
                        LibraryPane(library.documents, audio, reducedMotion, importing, { importPicker.launch(arrayOf("application/pdf")) }, ::openDocument)
                    }
                    entry<DocumentKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                        DocumentPane(key.id, store, pdf, playback, library.notes.filter { it.documentId == key.id }, audio, reducedMotion, { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }, ::recordThought)
                    }
                },
            )
        }

        if (message != null) AlertDialog(onDismissRequest = { message = null }, title = { Text("A quick note") }, text = { Text(message.orEmpty()) }, confirmButton = { TextButton(onClick = { message = null }) { Text("Got it") } })

        if (showVoices) ModalBottomSheet(onDismissRequest = { showVoices = false }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Label("OFFLINE VOICES")
                Text("A voice for your reading", style = MaterialTheme.typography.headlineMedium)
                Text("This build uses an installed offline English voice. Enhanced voice and transcription downloads will appear after their device checks are complete.")
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, null)
                        Spacer(Modifier.width(16.dp))
                        Column { Text("System English", fontWeight = FontWeight.SemiBold); Text("Installed availability is checked when listening starts", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }.onFailure { message = "Open Android Settings and search for Text-to-speech output." } }) { Text("Open Android voice settings") }
                Spacer(Modifier.height(16.dp))
            }
        }

        if (showSettings) AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Make yourself comfortable") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Text follows your system size. The page view keeps the original PDF; passage view gives you comfortable, accessible type.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reduce decorative motion", Modifier.weight(1f))
                        Switch(reducedMotion, { reducedMotion = it; preferences.edit().putBoolean("reducedMotion", it).apply(); haptic(view) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Touch feedback", Modifier.weight(1f))
                        Switch(touchFeedback, { touchFeedback = it; preferences.edit().putBoolean("touchFeedback", it).apply(); haptic(view) })
                    }
                    Text("eReader 0.01 · local content processing", style = MaterialTheme.typography.bodySmall)
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
                        Text(noteCapture.draft!!.quote, maxLines = 4, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
                    }
                }
                (noteCapture.error ?: recording.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Your recording is saved before you resume. Transcription is waiting for a compatible local model in this build.", style = MaterialTheme.typography.bodySmall)
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

        if (typedNote) AlertDialog(
            onDismissRequest = { if (!typedSaving) { typedNote = false; playback.endCapture() } },
            title = { Text("Write your thought") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Microphone access is off. Your thought keeps the passage where you paused.", style = MaterialTheme.typography.bodySmall)
                    Text("Attached to page ${typedPage + 1}", style = MaterialTheme.typography.labelMedium)
                    if (typedQuote.isNotBlank()) Text(typedQuote, fontFamily = FontFamily.Serif, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
    }
}

@Composable
private fun LibraryPane(documents: List<LibraryDocument>, audio: PlaybackSnapshot, reducedMotion: Boolean, importing: Boolean, import: () -> Unit, open: (String) -> Unit) {
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
                    Text(if (audio.documentId != null) "PICK UP WHERE YOU LEFT OFF" else "YOUR NEXT LISTEN", color = Color(0xFFE5EDD9), style = MaterialTheme.typography.labelSmall)
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
                        Text("${document.pageCount} pages · ${if (document.passageCount > 0) "Embedded text" else "Original pages"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
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
private fun DocumentPane(id: String, store: ReaderStore, pdf: PdfAdapter, playback: PlaybackCoordinator, notes: List<VoiceNote>, audio: PlaybackSnapshot, reducedMotion: Boolean, back: () -> Unit, record: () -> Unit) {
    var document by remember(id) { mutableStateOf<ReaderContents?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var mode by rememberSaveable(id) { mutableStateOf("passage") }
    var following by rememberSaveable(id) { mutableStateOf(true) }
    var page by rememberSaveable(id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(id) { runCatching { store.contents(id) }.onSuccess { document = it }.onFailure { error = "This document could not be opened." } }
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(audio.passageId, following, document) {
        if (following && audio.documentId == id) {
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
                    if (contents.passages.isNotEmpty() && audio.documentId != id) FilledTonalIconButton(onClick = {
                        val saved = store.snapshot.value.positions.firstOrNull { it.documentId == id }
                        val index = contents.passages.indexOfFirst { it.id == saved?.passageId }.coerceAtLeast(0)
                        playback.listen(contents, index, saved?.offsetMillis ?: 0); haptic(view, true)
                    }) { Icon(Icons.Default.PlayArrow, "Start listening") }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(mode == "passage", { mode = "passage"; haptic(view) }, { Text("Passages") })
                    FilterChip(mode == "page", { mode = "page"; haptic(view) }, { Text("Original PDF") })
                    if (!wide) FilterChip(mode == "notes", { mode = "notes"; haptic(view) }, { Text("Thoughts (${notes.size})") })
                    if (!following && audio.documentId == id) TextButton(onClick = { following = true; haptic(view) }) { Text("Follow") }
                }
                Spacer(Modifier.height(8.dp))
                when (mode) {
                    "page" -> OriginalPage(contents.document, page, pdf, { page = it; following = false }, Modifier.weight(1f))
                    "notes" -> NotesPane(notes, store, { note ->
                        val index = contents.passages.indexOfFirst { it.id == note.passageId }
                        if (index >= 0) { mode = "passage"; following = true; playback.listen(contents, index) }
                    }, Modifier.weight(1f))
                    else -> {
                        if (contents.passages.isEmpty()) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text("The original is ready.", style = MaterialTheme.typography.headlineMedium)
                                Text("This document has no supported embedded-text listening in this build. Scanned-page recognition and text extraction on older Android versions are still to be qualified.")
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
                                        Text("PAGE ${passage.pageIndex + 1}${if (active) " · CURRENT PASSAGE" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Text(passage.sourceText, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 21.sp, lineHeight = 34.sp))
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
}

@Composable
private fun OriginalPage(document: LibraryDocument, page: Int, pdf: PdfAdapter, selectPage: (Int) -> Unit, modifier: Modifier) {
    var bitmap by remember(document.id, page) { mutableStateOf<Bitmap?>(null) }
    var error by remember(document.id, page) { mutableStateOf<String?>(null) }
    LaunchedEffect(document.id, page) { runCatching { pdf.render(document.filePath, page) }.onSuccess { bitmap = it }.onFailure { error = it.message ?: "This page could not be rendered." } }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selectPage(page - 1) }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Previous page") }
            Text("Page ${page + 1} of ${document.pageCount}", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { selectPage(page + 1) }, enabled = page + 1 < document.pageCount) { Icon(Icons.Default.ChevronRight, "Next page") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFE8E8E3)).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), "Original PDF page ${page + 1}. Use Passages for accessible text.", Modifier.fillMaxWidth().widthIn(max = 900.dp), contentScale = ContentScale.FillWidth)
            else if (error != null) Text(error!!, Modifier.padding(24.dp))
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
                    Text(note.quote, fontFamily = FontFamily.Serif, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(note.editedTranscript ?: note.machineTranscript?.takeIf { it.isNotBlank() } ?: when (note.status) {
                        "interrupted" -> "Recording was interrupted. Reopen the app to recover any captured audio."
                        else -> "Recording saved · transcription unavailable in this build"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (note.durationMillis > 0 && note.recordingPath.isNotBlank() && java.io.File(note.recordingPath).exists()) IconButton(onClick = {
                            playback.playNote(note)
                        }) { Icon(if (audio.noteId == note.id && audio.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (audio.noteId == note.id && audio.playing) "Pause voice note" else "Play voice note") }
                        TextButton(onClick = { jump(note) }) { Text("Return to passage") }
                        IconButton(onClick = { editing = note; text = note.editedTranscript ?: note.machineTranscript.orEmpty(); saveError = null }) { Icon(Icons.Default.Edit, "Write or edit transcript") }
                    }
                }
            }
        }
    }
    if (editing != null) AlertDialog(
        onDismissRequest = { if (!saving) editing = null },
        title = { Text("Your words") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun ListeningDock(audio: PlaybackSnapshot, playback: PlaybackCoordinator, available: Boolean, record: () -> Unit) {
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
            if (audio.noteId != null) TextButton(onClick = { haptic(view); playback.returnToReading() }) { Text("Return to reading") }
        }
    }
}

@Composable
private fun TransportControls(audio: PlaybackSnapshot, playback: PlaybackCoordinator, available: Boolean, record: () -> Unit) {
    val view = LocalView.current
    IconButton(onClick = { haptic(view); playback.skip(-1) }, enabled = available && audio.noteId == null && !audio.preparing) { Icon(Icons.Default.SkipPrevious, "Previous passage") }
    FilledIconButton(onClick = { haptic(view, true); playback.toggle() }, enabled = available, modifier = Modifier.size(52.dp)) {
        if (audio.preparing) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
        else Icon(if (audio.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (audio.playing) "Pause" else "Play")
                }
    IconButton(onClick = { haptic(view); playback.skip(1) }, enabled = available && audio.noteId == null && !audio.preparing) { Icon(Icons.Default.SkipNext, "Next passage") }
    IconButton(onClick = record, enabled = available && audio.noteId == null && audio.durationMillis > 0 && !audio.preparing) { Icon(Icons.Default.Mic, "Pause and record a thought") }
}

@Composable
private fun BookMark(title: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp)).background(Moss), contentAlignment = Alignment.Center) {
        Text(title.take(1).uppercase(), color = Color(0xFFF0ECD9), fontFamily = FontFamily.Serif, fontSize = 22.sp)
    }
}

@Composable
private fun Label(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }

private fun clock(milliseconds: Long): String = "%d:%02d".format(milliseconds / 60_000, milliseconds / 1000 % 60)
