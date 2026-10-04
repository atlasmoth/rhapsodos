package com.osuji.ereaderapp.data

import android.content.Context
import android.net.Uri
import com.osuji.ereaderapp.audio.validateRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

@Serializable private data class ArchiveFile(val path: String, val sha256: String, val bytes: Long)

class DocumentArchives(private val context: Context, private val store: ReaderStore) {
    suspend fun export(documentId: String, destination: Uri) = withContext(Dispatchers.IO) {
        val manifest = store.json.parseToJsonElement(store.exportManifest(documentId)).jsonObject
        val files = linkedMapOf<String, File>()
        val document = manifest.getValue("document").jsonObject
        files["document.pdf"] = owned(document.getValue("filePath").jsonPrimitive.content)
        val notes = manifest.getValue("notes").jsonArray.mapIndexed { index, element ->
            val note = element.jsonObject
            val original = note.getValue("recordingPath").jsonPrimitive.content
            if (original.isNotBlank()) {
                val path = "audio/$index.wav"
                files[path] = owned(original)
                validateRecording(files.getValue(path))
                replace(note, "recordingPath", JsonPrimitive(path))
            } else note
        }
        require(files.values.all { it.isFile }) { "A referenced PDF or recording is missing. Your data has not been changed." }
        require(files.values.sumOf { it.length() } <= 600L * 1024 * 1024) { "This archive exceeds the 600 MB export limit." }
        val portable = replace(replace(manifest, "document", replace(document, "filePath", JsonPrimitive("document.pdf"))), "notes", JsonArray(notes))
        val assets = files.map { (path, file) -> ArchiveFile(path, digest(file), file.length()) }
        require(assets.size <= 100_001) { "This document contains too many recordings to export safely." }
        val envelope = JsonObject(mapOf("manifest" to portable, "files" to store.json.parseToJsonElement(store.json.encodeToString(assets))))
        val manifestBytes = envelope.toString().toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= 50_000_000) { "This document's prepared text and thoughts exceed the 50 MB archive manifest limit. Your data has not been changed." }
        context.contentResolver.openOutputStream(destination, "wt")?.use { target ->
            ZipOutputStream(target.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestBytes)
                zip.closeEntry()
                files.forEach { (path, file) ->
                    coroutineContext.ensureActive()
                    zip.putNextEntry(ZipEntry(path))
                    file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        } ?: error("The file provider could not save the archive.")
    }

    suspend fun restore(source: Uri): String = withContext(Dispatchers.IO) {
        val token = UUID.randomUUID().toString()
        val staging = File(context.filesDir, "restored/.$token").apply { check(mkdirs() || isDirectory) }
        val destination = File(context.filesDir, "restored/$token")
        var committed = false
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    require(zip.nextEntry?.name == "manifest.json") { "This is not a Rhapsodos document archive." }
                    val envelope = store.json.parseToJsonElement(readBounded(zip, 50_000_000).toString(Charsets.UTF_8)).jsonObject
                    val manifest = envelope.getValue("manifest").jsonObject
                    val document = manifest.getValue("document").jsonObject
                    val id = document.getValue("id").jsonPrimitive.content
                    require(store.snapshot.value.documents.none { it.id == id }) { "This document is already in your library." }
                    val assets = store.json.decodeFromString<List<ArchiveFile>>(envelope.getValue("files").toString())
                    require(assets.isNotEmpty() && assets.size <= 100_001 && assets.map { it.path }.distinct().size == assets.size)
                    require(assets.all { it.path == "document.pdf" || it.path.matches(Regex("audio/[0-9]+\\.wav")) }) { "The archive contains an unsafe file path." }
                    require(assets.any { it.path == "document.pdf" && it.bytes > 0 }) { "The archive does not contain its original PDF." }
                    require(assets.all { it.bytes in 0..600L * 1024 * 1024 && it.sha256.matches(Regex("[a-f0-9]{64}")) } && assets.sumOf { it.bytes } <= 600L * 1024 * 1024)
                    val remaining = assets.associateBy { it.path }.toMutableMap()
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = zip.nextEntry ?: break
                        val expected = remaining.remove(entry.name) ?: error("The archive contains an unexpected or duplicate file.")
                        val file = File(staging, entry.name)
                        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= expected.bytes) { "An archive file exceeds its declared size." }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                        require(file.length() == expected.bytes && digest(file) == expected.sha256) { "An archive file failed its integrity check." }
                        if (entry.name.startsWith("audio/")) validateRecording(file)
                    }
                    require(remaining.isEmpty()) { "The archive is incomplete." }
                    require(document.getValue("filePath").jsonPrimitive.content == "document.pdf")
                    val recordings = manifest.getValue("notes").jsonArray.map { it.jsonObject.getValue("recordingPath").jsonPrimitive.content }.filter { it.isNotBlank() }
                    require(recordings.distinct().size == recordings.size && assets.map { it.path }.toSet() == recordings.toSet() + "document.pdf") { "The archive contains unreferenced or duplicate recordings." }
                    val notes = manifest.getValue("notes").jsonArray.map { element ->
                        val note = element.jsonObject
                        val path = note.getValue("recordingPath").jsonPrimitive.content
                        if (path.isNotBlank()) {
                            require(path.matches(Regex("audio/[0-9]+\\.wav"))) { "A recording uses an unsafe archive path." }
                            require(assets.any { it.path == path }) { "A recording is missing from the archive." }
                            val duration = validateRecording(File(staging, path))
                            require(kotlin.math.abs(duration - note.getValue("durationMillis").jsonPrimitive.content.toLong()) <= 2) { "A recording does not match its saved duration." }
                            replace(note, "recordingPath", JsonPrimitive(File(destination, path).absolutePath))
                        } else note
                    }
                    File(staging, "audio").takeIf { it.isDirectory }?.let { syncDirectory(it, context.filesDir) }
                    syncDirectory(staging, context.filesDir)
                    check(staging.renameTo(destination)) { "The archive files could not be saved." }
                    syncDirectory(destination.parentFile!!, context.filesDir)
                    val local = replace(replace(manifest, "document", replace(document, "filePath", JsonPrimitive(File(destination, "document.pdf").absolutePath))), "notes", JsonArray(notes))
                    store.restoreManifest(local.toString())
                    committed = true
                    return@withContext id
                }
            } ?: error("The file provider could not open the archive.")
        } finally { if (!committed) { staging.deleteRecursively(); destination.deleteRecursively() } }
    }

    private fun owned(path: String): File {
        val root = context.filesDir.canonicalFile
        return File(path).canonicalFile.also { require(it.path.startsWith(root.path + File.separator)) { "Only app-owned files can be exported." } }
    }
    private fun replace(source: JsonObject, key: String, value: JsonElement) = JsonObject(source.toMutableMap().apply { put(key, value) })
    private fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var count = 0
        while (true) { val read = input.read(buffer); if (read < 0) break; count += read; require(count <= maximum) { "Archive manifest is too large." }; output.write(buffer, 0, read) }
        return output.toByteArray()
    }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
