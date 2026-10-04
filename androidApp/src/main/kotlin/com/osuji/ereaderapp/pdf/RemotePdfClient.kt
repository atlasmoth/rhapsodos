package com.osuji.ereaderapp.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PdfWorkerResponse(val json: String? = null, val bitmap: Bitmap? = null)

class RemotePdfClient(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serial = Mutex()
    private var service: IPdfWorker? = null
    private var connection: ServiceConnection? = null
    private var pending: CompletableDeferred<IPdfWorker>? = null
    private var idleTimer: Job? = null
    @Volatile private var releaseAfterCurrent = false

    internal suspend fun request(path: String, operation: String, pageIndex: Int = 0, width: Int = 1400): PdfWorkerResponse = serial.withLock {
        try {
            withTimeout(45_000) {
                val remote = connect()
                val pipe = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        val file = File(path)
                        val identity = java.security.MessageDigest.getInstance("SHA-256").digest("${file.canonicalPath}:${file.length()}:${file.lastModified()}".toByteArray()).joinToString("") { "%02x".format(it) }
                        remote.request(descriptor, identity, operation, pageIndex, width)
                    }
                }
                suspendCancellableCoroutine { continuation ->
                    val task = scope.launch(Dispatchers.IO) {
                        try {
                            val response = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { raw ->
                                val input = DataInputStream(raw)
                                when (input.readUnsignedByte()) {
                                    0 -> error(input.readUTF())
                                    1 -> {
                                        val length = input.readInt()
                                        require(length in 0..4_100_000) { "The PDF worker returned too much text." }
                                        val bytes = ByteArray(length).also { input.readFully(it) }
                                        require(input.read() == -1) { "The PDF worker returned an invalid page." }
                                        PdfWorkerResponse(json = bytes.toString(Charsets.UTF_8))
                                    }
                                    2 -> {
                                        val expectedWidth = input.readInt(); val expectedHeight = input.readInt()
                                        require(expectedWidth in 1..2400 && expectedHeight in 1..6000 && expectedWidth.toLong() * expectedHeight <= 6_000_000)
                                        val output = java.io.ByteArrayOutputStream()
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 32_000_000); output.write(buffer, 0, count) }
                                        val bytes = output.toByteArray()
                                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                        require(bounds.outWidth == expectedWidth && bounds.outHeight == expectedHeight)
                                        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })) { "The original page could not be displayed." }
                                        PdfWorkerResponse(bitmap = bitmap)
                                    }
                                    else -> error("The PDF worker returned an invalid response.")
                                }
                            }
                            if (continuation.isActive) continuation.resume(response, onCancellation = { _, value, _ -> value.bitmap?.recycle() }) else response.bitmap?.recycle()
                        } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                    }
                    continuation.invokeOnCancellation { runCatching { pipe.close() }; task.cancel(); release() }
                }
            }
        } catch (failure: Exception) {
            release()
            if (failure is kotlinx.coroutines.TimeoutCancellationException) error("PDF preparation took too long. Your original and prepared pages are safe; retry to continue.")
            throw failure
        } finally {
            if (releaseAfterCurrent) { releaseAfterCurrent = false; release() }
            else scope.launch {
                idleTimer?.cancel()
                idleTimer = launch { delay(30_000); if (serial.tryLock()) try { release() } finally { serial.unlock() } }
            }
        }
    }

    private suspend fun connect(): IPdfWorker = withContext(Dispatchers.Main.immediate) {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withContext it }
        pending?.let { return@withContext it.await() }
        connection?.let { runCatching { context.unbindService(it) } }
        val ready = CompletableDeferred<IPdfWorker>()
        pending = ready
        val created = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                service = IPdfWorker.Stub.asInterface(binder)
                pending = null
                ready.complete(checkNotNull(service))
            }
            override fun onServiceDisconnected(name: ComponentName) { if (connection === this) service = null; ready.completeExceptionally(IllegalStateException("PDF preparation stopped. Your original and prepared pages are safe; retry to continue.")) }
            override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
            override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
        }
        connection = created
        check(context.bindService(Intent(context, IsolatedPdfService::class.java), created, Context.BIND_AUTO_CREATE)) { "The isolated PDF worker could not start." }
        try { ready.await() }
        catch (failure: Exception) { release(); throw failure }
    }

    fun release() = scope.launch {
        idleTimer?.cancel(); idleTimer = null
        val previous = connection
        service = null; pending?.cancel(); pending = null; connection = null
        if (previous != null) runCatching { context.unbindService(previous) }
    }

    fun releaseWhenIdle() { if (serial.tryLock()) try { release() } finally { serial.unlock() } else releaseAfterCurrent = true }
}
