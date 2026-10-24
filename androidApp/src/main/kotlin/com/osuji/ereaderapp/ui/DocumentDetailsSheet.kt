package com.osuji.ereaderapp.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.osuji.ereaderapp.data.LibraryDocument
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentDetailsSheet(document: LibraryDocument, dismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    var fileSize by remember(document.id, document.filePath, document.sourceRevision) { mutableStateOf<Long?>(null) }
    var loading by remember(document.id, document.filePath, document.sourceRevision) { mutableStateOf(true) }
    LaunchedEffect(document.id, document.filePath, document.sourceRevision) {
        fileSize = withContext(Dispatchers.IO) {
            runCatching {
                Files.readAttributes(Paths.get(document.filePath), BasicFileAttributes::class.java)
                    .takeIf { it.isRegularFile }?.size()
            }.getOrNull()
        }
        loading = false
    }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).navigationBarsPadding().padding(horizontal = 24.dp)) {
            SheetHeader("Document details", done = dismiss)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SelectionContainer {
                    Text(document.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
                }
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        DocumentDetailRow("Pages", document.pageCount.takeIf { it > 0 }?.let { NumberFormat.getIntegerInstance(locale).format(it) } ?: "Unavailable")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DocumentDetailRow("File size", if (loading) "Loading…" else fileSize?.let { Formatter.formatFileSize(context, it) } ?: "Unavailable")
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}
