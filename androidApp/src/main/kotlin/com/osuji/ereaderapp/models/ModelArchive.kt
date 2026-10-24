package com.osuji.ereaderapp.models

import com.osuji.ereaderapp.data.PackFile
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.coroutines.coroutineContext

internal suspend fun extractModelArchive(archive: File, files: List<PackFile>, destination: File) {
    require(archive.canonicalFile == archive.absoluteFile && !Files.isSymbolicLink(archive.toPath())) { "The model archive path is unsafe." }
    require(destination.canonicalFile == destination.absoluteFile && !Files.isSymbolicLink(destination.toPath())) { "The model staging path is unsafe." }
    val entries = readModelArchive(archive, files)
    clearModelStaging(destination)
    check(destination.mkdirs()) { "Model files could not be prepared. Free storage and retry." }
    try {
        RandomAccessFile(archive, "r").use { input ->
            for (entry in entries.filterNot { it.directory }) {
                coroutineContext.ensureActive()
                val expected = files.first { it.path == entry.path }
                val target = File(destination, entry.path)
                check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                require(target.canonicalPath == target.absolutePath && target.canonicalPath.startsWith(destination.path + File.separator)) { "The model archive contains an unsafe file path." }
                val hash = MessageDigest.getInstance("SHA-256")
                val crc = CRC32()
                var written = 0L
                input.seek(entry.dataOffset)
                FileOutputStream(target).use { output ->
                    readArchiveEntry(input, entry) { buffer, count ->
                        written += count
                        require(written <= expected.bytes) { "An extracted model file exceeds its pinned size." }
                        hash.update(buffer, 0, count)
                        crc.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    require(written == expected.bytes && crc.value == entry.crc && hash.digest().hex() == expected.sha256) { "An extracted model file failed its integrity check." }
                    output.fd.sync()
                }
            }
        }
    } catch (failure: Exception) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { clearModelStaging(destination) }
        throw failure
    }
}

internal suspend fun clearModelStaging(directory: File) {
    val execution = coroutineContext
    if (!Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            execution.ensureActive()
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
            execution.ensureActive()
            if (failure != null) throw failure
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

private data class ArchiveEntry(val path: String, val directory: Boolean, val flags: Int, val method: Int, val crc: Long, val compressed: Long, val bytes: Long, val offset: Long, val dataOffset: Long = 0)

private suspend fun readArchiveEntry(input: RandomAccessFile, entry: ArchiveEntry, write: (ByteArray, Int) -> Unit) {
    val compressed = ByteArray(64 * 1024)
    var remaining = entry.compressed
    if (entry.method == 0) {
        while (remaining > 0) {
            coroutineContext.ensureActive()
            val count = minOf(remaining, compressed.size.toLong()).toInt()
            input.readFully(compressed, 0, count)
            remaining -= count
            write(compressed, count)
        }
        return
    }
    val inflater = Inflater(true)
    val expanded = ByteArray(64 * 1024)
    try {
        while (!inflater.finished()) {
            coroutineContext.ensureActive()
            if (inflater.needsInput()) {
                require(remaining > 0) { "The compressed model file is incomplete." }
                val count = minOf(remaining, compressed.size.toLong()).toInt()
                input.readFully(compressed, 0, count)
                remaining -= count
                inflater.setInput(compressed, 0, count)
            }
            val readBefore = inflater.bytesRead
            val count = inflater.inflate(expanded)
            require(!inflater.needsDictionary() && (count > 0 || inflater.bytesRead > readBefore || inflater.finished())) { "The model archive compression is invalid." }
            if (count > 0) write(expanded, count)
        }
        require(remaining == 0L && inflater.remaining == 0) { "The compressed model file contains undeclared data." }
    } finally { inflater.end() }
}

private suspend fun readModelArchive(archive: File, files: List<PackFile>): List<ArchiveEntry> {
    val expected = files.associateBy { it.path }
    require(files.isNotEmpty() && expected.size == files.size && files.size <= 256) { "The model file manifest is invalid." }
    val directories = files.flatMap { file ->
        val parts = file.path.split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_. -]+")) }) { "The model manifest contains an unsafe path." }
        (1 until parts.size).map { parts.take(it).joinToString("/") }
    }.toSet()
    val declaredPaths = expected.keys + directories
    require(declaredPaths.size == expected.size + directories.size && declaredPaths.map { it.lowercase(Locale.ROOT) }.toSet().size == declaredPaths.size) { "The model manifest contains conflicting paths." }
    return RandomAccessFile(archive, "r").use { input ->
        val end = input.length() - 22
        require(end >= 0) { "The model archive is incomplete." }
        input.seek(end)
        require(input.u32() == 0x06054b50L && input.u16() == 0 && input.u16() == 0) { "The model archive format is unsupported." }
        val diskCount = input.u16()
        val count = input.u16()
        val centralBytes = input.u32()
        val centralOffset = input.u32()
        require(input.u16() == 0 && count == diskCount && count in files.size..declaredPaths.size && centralOffset + centralBytes == end) { "The model archive directory is invalid." }
        input.seek(centralOffset)
        val entries = mutableListOf<ArchiveEntry>()
        val names = mutableSetOf<String>()
        repeat(count) {
            coroutineContext.ensureActive()
            require(input.u32() == 0x02014b50L) { "The model archive directory is corrupt." }
            val creator = input.u16() shr 8
            val version = input.u16()
            val flags = input.u16()
            val method = input.u16()
            input.skipBytes(4)
            val crc = input.u32()
            val compressed = input.u32()
            val bytes = input.u32()
            val nameLength = input.u16()
            val extraLength = input.u16()
            val commentLength = input.u16()
            val disk = input.u16()
            input.skipBytes(2)
            val attributes = input.u32()
            val offset = input.u32()
            require(creator in setOf(0, 3) && version <= 20 && flags in setOf(0, 0x800) && method in setOf(0, 8) && disk == 0 && extraLength == 0 && commentLength == 0 && nameLength in 1..1024) { "The model archive contains unsupported entries." }
            val rawName = ByteArray(nameLength).also(input::readFully)
            require(rawName.all { it.toInt() in 32..126 }) { "The model archive contains an invalid file name." }
            val name = rawName.toString(Charsets.US_ASCII)
            val directory = name.endsWith('/')
            val path = name.removeSuffix("/")
            val mode = (attributes ushr 16).toInt() and 0xf000
            require(mode == 0 || mode == if (directory) 0x4000 else 0x8000) { "The model archive contains a link or unsupported file type." }
            require((attributes and 0x10L == 0L || directory) && (attributes and 0x8L == 0L)) { "The model archive contains an unsupported file type." }
            require(names.add(path.lowercase(Locale.ROOT))) { "The model archive contains duplicate paths." }
            if (directory) require(path in directories && bytes == 0L && compressed == 0L && method == 0 && crc == 0L) { "The model archive contains an unexpected directory." }
            else require(expected[path]?.bytes == bytes && bytes > 0 && compressed > 0 && (method != 0 || compressed == bytes)) { "The model archive does not match its file manifest." }
            require(offset < centralOffset && compressed < centralOffset) { "The model archive contains an invalid file range." }
            entries += ArchiveEntry(path, directory, flags, method, crc, compressed, bytes, offset)
            require(input.filePointer <= end) { "The model archive directory is corrupt." }
        }
        require(input.filePointer == end && entries.filterNot { it.directory }.map { it.path }.toSet() == expected.keys) { "The model archive is missing required files." }
        var nextOffset = 0L
        val bounded = entries.sortedBy { it.offset }.map { entry ->
            coroutineContext.ensureActive()
            require(entry.offset == nextOffset) { "The model archive contains overlapping or undeclared data." }
            input.seek(entry.offset)
            require(input.u32() == 0x04034b50L && input.u16() <= 20 && input.u16() == entry.flags && input.u16() == entry.method) { "The model archive file header is invalid." }
            input.skipBytes(4)
            require(input.u32() == entry.crc && input.u32() == entry.compressed && input.u32() == entry.bytes) { "The model archive file metadata is inconsistent." }
            val length = input.u16()
            require(input.u16() == 0 && length in 1..1024) { "The model archive file header is unsupported." }
            val name = ByteArray(length).also(input::readFully).toString(Charsets.US_ASCII)
            require(name == entry.path + if (entry.directory) "/" else "") { "The model archive file paths are inconsistent." }
            nextOffset = input.filePointer + entry.compressed
            require(nextOffset <= centralOffset) { "The model archive file range is invalid." }
            entry.copy(dataOffset = input.filePointer)
        }
        require(nextOffset == centralOffset) { "The model archive contains undeclared data." }
        bounded
    }
}

private fun RandomAccessFile.u16(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
private fun RandomAccessFile.u32(): Long = u16().toLong() or (u16().toLong() shl 16)
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
