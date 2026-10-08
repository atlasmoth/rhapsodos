package com.osuji.ereaderapp.models

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import com.osuji.ereaderapp.data.ModelInstall
import com.osuji.ereaderapp.data.ModelPack
import com.osuji.ereaderapp.data.ModelCatalogStatus
import com.ereader.shared.ModelCatalogSource
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.data.VerifiedPackFile
import com.osuji.ereaderapp.data.syncDirectory
import com.osuji.ereaderapp.speech.OfflineSpeechRuntime
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext

data class ModelState(
    val catalog: List<ModelPack> = emptyList(), val installs: List<ModelInstall> = emptyList(),
    val selectedVoice: String = "system", val voiceId: String = "bella", val selectedTranscription: String = "android-moonshine-english",
    val checking: Set<String> = emptySet(), val removing: Set<String> = emptySet(), val error: String? = null,
    val catalogStatus: ModelCatalogStatus = ModelCatalogStatus(), val refreshing: Boolean = false,
    val catalogMessage: String? = null, val changingTranscription: Boolean = false, val initializing: Boolean = true,
    val checks: Map<String, Long> = emptyMap(),
    val unavailable: Map<String, String> = emptyMap(),
)

fun supportsOfflineSpeechAbi(abis: List<String>): Boolean = abis.any { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") }

class ModelManager(private val context: Context, private val store: ReaderStore, val runtime: OfflineSpeechRuntime, private val scope: CoroutineScope) {
    private val preferences = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(ModelState(selectedVoice = preferences.getString("voicePack", "system") ?: "system", voiceId = preferences.getString("voiceId", "bella") ?: "bella", selectedTranscription = preferences.getString("transcriptionPack", "android-moonshine-english") ?: "android-moonshine-english"))
    val state = mutableState.asStateFlow()
    private val ready = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val readyEvents = ready.asSharedFlow()
    var beforeModelRemoval: (suspend (String) -> Unit)? = null
    var beforeVerification: (suspend () -> Unit)? = null
    var runIdleVerification: (suspend (suspend () -> Unit) -> Unit)? = null
    var beforeTranscriptionChange: (suspend () -> Unit)? = null
    var transcriptionSelectionAllowed: () -> Boolean = { false }
    var catalogueWorkloadIdle: () -> Boolean = { false }
    val catalogRefreshConfigured: Boolean get() = ModelCatalogSource.httpsUrl.isNotBlank() && ModelCatalogSource.publicKeyX963.isNotBlank()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val validated = ConcurrentHashMap.newKeySet<String>()
    internal var downloadService: ModelDownloadService? = null
    internal fun hasDownloads() = jobs.isNotEmpty()
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 30_000; socketTimeoutMillis = 45_000; requestTimeoutMillis = 600_000 }
    }

    fun initialize() = scope.launch {
        try {
            val catalog = store.modelCatalog()
            val installs = store.modelInstalls()
            val catalogStatus = store.modelCatalogStatus()
            mutableState.update { it.copy(catalog = catalog, installs = installs, catalogStatus = catalogStatus, checking = installs.filter { record -> record.status == "ready" }.map { record -> record.packId }.toSet()) }
            installs.filter { it.status == "ready" }.forEach { install ->
                val pack = catalog.firstOrNull { it.id == install.packId }
                val verified = if (pack != null) withContext(Dispatchers.IO) {
                    try {
                        val directory = owned(install.installedPath)
                        require(pack.files.all { File(directory, it.path).length() == it.bytes && digest(File(directory, it.path)) == it.sha256 })
                        val reason = runtimeCompatibility(pack)
                        if (reason == null) {
                            beforeVerification?.invoke()
                            validateRuntime(pack, directory)
                            true
                        } else {
                            mutableState.update { it.copy(unavailable = it.unavailable + (pack.id to reason)) }
                            false
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                } else false
                if (verified) validated += install.packId
                else if (install.packId !in state.value.unavailable) store.invalidateModel(install.packId, install.generation, "Installed files failed their check. Download this pack again.")
                mutableState.update { it.copy(checking = it.checking - install.packId) }
            }
            installs.filter { it.status in setOf("downloading", "verifying") }.forEach { install ->
                val pack = catalog.firstOrNull { it.id == install.packId }
                val recovered = if (pack != null && install.status == "verifying" && runtimeCompatibility(pack) == null) withContext(Dispatchers.IO) {
                    val directory = owned("Models/${pack.id}/${pack.version}")
                    if (directory.isDirectory && pack.files.all { File(directory, it.path).length() == it.bytes && digest(File(directory, it.path)) == it.sha256 }) {
                        try { beforeVerification?.invoke(); validateRuntime(pack, directory); store.activateModel(pack.id, install.generation, "Models/${pack.id}/${pack.version}", pack.files.map { VerifiedPackFile(it.path, it.sha256, it.bytes) }); validated += pack.id; true }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                    } else false
                } else false
                if (!recovered) store.updateModelInstall(install.packId, install.generation, "paused", install.bytesDownloaded)
            }
            mutableState.value = state.value.copy(catalog = catalog, installs = store.modelInstalls(), error = null)
            if (state.value.selectedVoice != "system" && installed(state.value.selectedVoice) == null) mutableState.value = state.value.copy(error = "Your selected voice is unavailable. Choose a voice or download it again.")
            state.value.installs.filter { it.status == "ready" }.forEach { ready.emit(it.packId) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.value = state.value.copy(error = "Offline downloads could not be loaded. Reopen to retry.") }
        finally { mutableState.update { it.copy(initializing = false) } }
    }

    fun compatibility(pack: ModelPack): String? {
        runtimeCompatibility(pack)?.let { return it }
        if (StatFs(context.filesDir.absolutePath).availableBytes < pack.totalBytes + 32L * 1024 * 1024) return "Free more storage before downloading"
        return null
    }

    private fun runtimeCompatibility(pack: ModelPack): String? {
        if (pack.adapter !in setOf("android-kitten-english", "android-moonshine-english")) return "This model needs an app update before it can run."
        if (!supportsOfflineSpeechAbi(Build.SUPPORTED_ABIS.toList())) return "This device's processor is not supported by this offline pack. You can still use the installed Android voice, read and save voice notes."
        if (Build.VERSION.SDK_INT < pack.minimumOS) return "Requires Android API ${pack.minimumOS} or newer"
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (memory.totalMem / (1024 * 1024) < pack.minimumMemoryMB) return "Requires ${pack.minimumMemoryMB} MB of device memory"
        return null
    }

    fun installed(packId: String): File? = state.value.installs.firstOrNull { it.packId == packId && it.status == "ready" && packId in validated && packId !in state.value.removing && it.installedPath.isNotBlank() }?.let {
        owned(it.installedPath).takeIf { directory -> directory.isDirectory }
    }

    fun selectedDirectory(): File? = if (state.value.selectedVoice == "system") null else installed(state.value.selectedVoice) ?: error("The selected offline voice is unavailable. Choose a voice or download it again.")
    fun asrDirectory(): File? = if (asrAdapter() == null) null else installed(state.value.selectedTranscription)
    fun asrAdapter(): String? = state.value.catalog.firstOrNull { it.id == state.value.selectedTranscription && it.capability == "asr" && it.adapter == "android-moonshine-english" }?.adapter

    suspend fun selectTranscription(packId: String) {
        withContext(Dispatchers.Main.immediate) {
            check(transcriptionSelectionAllowed()) { "Finish your recording before changing transcription." }
            check(!state.value.changingTranscription) { "Wait for transcription selection to finish." }
            check(packId == "off" || state.value.catalog.any { it.id == packId && it.capability == "asr" && it.adapter == "android-moonshine-english" } && installed(packId) != null) { "Download and check this transcription pack first." }
            if (state.value.selectedTranscription == packId) return@withContext
            mutableState.update { it.copy(changingTranscription = true) }
            try {
                beforeTranscriptionChange?.invoke()
                check(transcriptionSelectionAllowed()) { "Finish your recording before changing transcription." }
                check(packId == "off" || installed(packId) != null) { "This transcription pack is no longer available." }
                preferences.edit().putString("transcriptionPack", packId).apply()
                mutableState.update { it.copy(selectedTranscription = packId) }
            } finally {
                mutableState.update { it.copy(changingTranscription = false) }
                val selected = state.value.selectedTranscription
                if (selected != "off" && installed(selected) != null) ready.emit(selected)
            }
        }
    }

    fun refreshCatalog() = scope.launch(Dispatchers.Main.immediate) {
        if (state.value.refreshing) return@launch
        if (!catalogRefreshConfigured) { mutableState.update { it.copy(catalogMessage = "This app includes a bundled catalogue. Live catalogue updates are not configured yet.") }; return@launch }
        if (!canRefreshCatalog()) { mutableState.update { it.copy(catalogMessage = "Pause listening and wait for downloads and checks to finish before refreshing.") }; return@launch }
        mutableState.update { it.copy(refreshing = true, catalogMessage = null) }
        try {
            val payload = downloadModelCatalog(client, ModelCatalogSource.httpsUrl, ModelCatalogSource.publicKeyX963)
            check(canRefreshCatalog()) { "Refresh stopped because listening, a download or a check started. Try again when idle." }
            val accepted = store.acceptModelCatalog(payload, ::canRefreshCatalog)
            val catalog = store.modelCatalog()
            val status = store.modelCatalogStatus()
            mutableState.update { it.copy(catalog = catalog, catalogStatus = status, catalogMessage = if (accepted) "Model catalogue updated." else "Your model catalogue is up to date.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.update { it.copy(catalogMessage = failure.message ?: "The catalogue could not be refreshed. Your downloads are unchanged.") } }
        finally { mutableState.update { it.copy(refreshing = false) } }
    }

    private fun canRefreshCatalog(): Boolean = jobs.isEmpty() && state.value.checking.isEmpty() && state.value.removing.isEmpty() && !state.value.initializing && !state.value.changingTranscription && state.value.installs.none { it.status in setOf("downloading", "verifying") } && catalogueWorkloadIdle()

    fun checkOnDevice(packId: String) = scope.launch(Dispatchers.Main.immediate) {
        if (state.value.refreshing || !canRefreshCatalog()) { reportError("Pause listening and wait for downloads and checks to finish before checking this device."); return@launch }
        val pack = state.value.catalog.firstOrNull { it.id == packId } ?: return@launch
        val install = state.value.installs.firstOrNull { it.packId == packId && it.status == "ready" } ?: return@launch
        val directory = installed(packId) ?: return@launch
        mutableState.update { it.copy(checking = it.checking + packId, error = null) }
        try {
            withContext(Dispatchers.IO) {
                require(pack.files.all { File(directory, it.path).length() == it.bytes && digest(File(directory, it.path)) == it.sha256 }) { "Installed files failed their integrity check. Download this pack again." }
                if (!catalogueWorkloadIdle()) throw CancellationException("The check stopped because another task started.")
                validateRuntime(pack, directory, retryWhenInterrupted = false)
            }
        } catch (cancelled: CancellationException) {
            reportError("The check stopped because another task started. Your download is unchanged.")
            coroutineContext.ensureActive()
        } catch (failure: Exception) {
            validated -= packId
            store.invalidateModel(packId, install.generation, "This pack failed its check. Download it again.")
            refresh()
            reportError(failure.message ?: "This pack failed its check. Download it again.")
        } finally { mutableState.update { it.copy(checking = it.checking - packId) } }
    }

    fun selectVoice(packId: String, voiceId: String = state.value.voiceId) {
        require(packId !in state.value.removing) { "Wait for this download to finish being removed." }
        require(packId == "system" || installed(packId) != null) { "Download and verify this voice first." }
        require(packId == "system" || state.value.catalog.any { it.id == packId && it.capability == "tts" })
        require(voiceId in listOf("jasper", "bella", "bruno", "luna", "hugo", "rosie", "leo", "kiki"))
        preferences.edit().putString("voicePack", packId).putString("voiceId", voiceId).apply()
        mutableState.update { it.copy(selectedVoice = packId, voiceId = voiceId) }
    }

    fun download(packId: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { scope.launch(Dispatchers.Main.immediate) { download(packId) }; return }
        if (state.value.initializing || state.value.refreshing || packId in state.value.removing) return
        jobs[packId]?.let { previous ->
            if (!previous.isActive) scope.launch { previous.join(); download(packId) }
            return
        }
        val pack = state.value.catalog.firstOrNull { it.id == packId } ?: return
        compatibility(pack)?.let { mutableState.value = state.value.copy(error = it); return }
        val generation = UUID.randomUUID().toString()
        try { context.startForegroundService(Intent(context, ModelDownloadService::class.java)) }
        catch (failure: Exception) { reportError("Open Rhapsodos to start this download, then try again."); return }
        val task = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val staging = owned("Models/.staging/${pack.id}/${pack.version}")
            try {
                store.beginModelInstall(pack.id, generation)
                check(staging.mkdirs() || staging.isDirectory)
                var previousBytes = 0L
                for (entry in pack.files) {
                    require(entry.path.matches(Regex("[A-Za-z0-9_.-]+"))) { "This model contains an unsupported file path." }
                    val target = File(staging, entry.path)
                    if (target.length() != entry.bytes || digest(target) != entry.sha256) {
                        stream(entry.url, target, entry.bytes) { count -> update(pack.id, generation, "downloading", previousBytes + count) }
                    }
                    require(target.length() == entry.bytes && digest(target) == entry.sha256) { "Downloaded file failed its integrity check. Retry to download it again." }
                    previousBytes += entry.bytes
                    update(pack.id, generation, "downloading", previousBytes)
                }
                update(pack.id, generation, "verifying", pack.totalBytes)
                beforeVerification?.invoke()
                validateRuntime(pack, staging)
                coroutineContext.ensureActive()
                syncDirectory(staging, context.filesDir)
                val finalPath = "Models/${pack.id}/${pack.version}"
                val destination = owned(finalPath)
                check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
                val previous = File(destination.parentFile, ".${destination.name}.previous-$generation")
                if (destination.exists()) check(destination.renameTo(previous)) { "The previous model could not be retained safely." }
                try {
                    check(staging.renameTo(destination)) { "Verified model files could not be installed." }
                    syncDirectory(destination.parentFile!!, context.filesDir)
                    store.activateModel(pack.id, generation, finalPath, pack.files.map { VerifiedPackFile(it.path, it.sha256, it.bytes) })
                    validated += pack.id
                    previous.deleteRecursively()
                } catch (failure: Exception) {
                    if (previous.exists()) { destination.deleteRecursively(); previous.renameTo(destination) }
                    throw failure
                }
                refresh()
                ready.emit(pack.id)
            } catch (cancelled: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) { store.updateModelInstall(pack.id, generation, "paused", stagedBytes(staging, pack)); refresh() }
                throw cancelled
            } catch (failure: Exception) {
                store.updateModelInstall(pack.id, generation, "failed", stagedBytes(staging, pack), failure.message ?: "Download did not complete. Retry when you are ready.")
                refresh()
            } finally {
                val completed = coroutineContext[Job]
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                    jobs.remove(pack.id, completed)
                    if (jobs.isEmpty()) downloadService?.finishIfIdle()
                }
            }
        }
        jobs[packId] = task
        task.start()
    }

    fun reportError(error: String) { mutableState.update { it.copy(error = error) } }

    fun pause(packId: String) { jobs[packId]?.cancel() }
    fun pauseAll() { jobs.values.forEach { it.cancel() } }
    fun remove(packId: String) = scope.launch {
        if (packId in state.value.removing) return@launch
        if (packId in state.value.checking) { reportError("Wait for this download's integrity check before removing it."); return@launch }
        mutableState.update { it.copy(removing = it.removing + packId) }
        try {
            require(packId.matches(Regex("[a-z0-9-]+")) && state.value.catalog.any { it.id == packId })
            pause(packId)
            jobs[packId]?.join()
            beforeModelRemoval?.invoke(packId)
            runtime.close(state.value.catalog.first { it.id == packId }.adapter)
            validated -= packId
            withContext(Dispatchers.IO) {
                listOf(owned("Models/$packId"), owned("Models/.staging/$packId")).forEach { directory ->
                    check(!directory.exists() || directory.deleteRecursively()) { "Downloaded files could not be removed. Free storage and retry removal." }
                    if (directory.parentFile!!.isDirectory) syncDirectory(directory.parentFile!!, context.filesDir)
                }
            }
            store.removeModel(packId)
            if (state.value.selectedVoice == packId) selectVoice("system")
            refresh()
        } catch (failure: Exception) {
            withContext(kotlinx.coroutines.NonCancellable) {
                validated -= packId
                val install = store.modelInstalls().firstOrNull { it.packId == packId }
                if (install?.status == "ready") store.invalidateModel(packId, install.generation, "Removal did not finish. Retry removing the downloaded files.")
                refresh()
                reportError("This download could not be removed. Retry removing its downloaded files.")
            }
            if (failure is CancellationException) throw failure
        }
        finally { mutableState.update { it.copy(removing = it.removing - packId) } }
    }

    private suspend fun update(id: String, generation: String, status: String, bytes: Long) {
        store.updateModelInstall(id, generation, status, bytes)
        refresh()
    }
    private suspend fun refresh() { val inventory = store.modelInstalls(); mutableState.update { it.copy(installs = inventory) } }
    private fun stagedBytes(directory: File, pack: ModelPack) = pack.files.sumOf { File(directory, it.path).length().coerceAtMost(it.bytes) }
    private fun owned(path: String): File {
        val root = context.filesDir.canonicalFile
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "The model path is outside local storage." }
        return file
    }
    private fun digest(file: File): String {
        if (!file.isFile) return ""
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun validateRuntime(pack: ModelPack, directory: File, retryWhenInterrupted: Boolean = true) {
        try {
            while (true) {
                val start = android.os.SystemClock.elapsedRealtime()
                val check: suspend () -> Unit = { runtime.smoke(pack.adapter, directory) }
                try {
                    runIdleVerification?.invoke(check) ?: check()
                    mutableState.update { it.copy(checks = it.checks + (pack.id to (android.os.SystemClock.elapsedRealtime() - start))) }
                    return
                } catch (cancelled: CancellationException) {
                    coroutineContext.ensureActive()
                    if (!retryWhenInterrupted) throw cancelled
                    beforeVerification?.invoke()
                }
            }
        }
        catch (failure: LinkageError) { throw IllegalStateException("This device cannot load this offline pack. Use the installed Android voice or remove the download.", failure) }
    }

    private suspend fun stream(source: String, destination: File, expectedBytes: Long, progress: suspend (Long) -> Unit) {
        var offset = destination.length().takeIf { it < expectedBytes } ?: 0L
        if (offset == 0L) RandomAccessFile(destination, "rw").use { it.setLength(0) }
        var url = source
        repeat(8) {
            require(allowed(url)) { "The model download redirected outside its approved hosts." }
            var redirect: String? = null
            var restart = false
            client.prepareGet(url) { if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-") }.execute { response ->
                if (response.status.value in 300..399) {
                    redirect = response.headers[HttpHeaders.Location]?.let { URI(url).resolve(it).toString() }
                    require(redirect != null) { "The download server returned an invalid redirect." }
                } else {
                    require(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.PartialContent) { "The download server returned ${response.status.value}. Retry later." }
                    if (offset > 0 && response.status == HttpStatusCode.OK) {
                        offset = 0
                        RandomAccessFile(destination, "rw").use { it.setLength(0) }
                        restart = true
                    } else {
                        require(offset == 0L || response.headers[HttpHeaders.ContentRange]?.startsWith("bytes $offset-") == true) { "The download server returned an invalid resume range. Retry later." }
                        val channel = response.bodyAsChannel()
                        RandomAccessFile(destination, "rw").use { output ->
                            output.seek(offset)
                            val buffer = ByteArray(64 * 1024)
                            var lastUpdate = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = channel.readAvailable(buffer, 0, buffer.size)
                                if (count < 0) break
                                if (count == 0) continue
                                offset += count
                                require(offset <= expectedBytes) { "The download exceeds its pinned size." }
                                output.write(buffer, 0, count)
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastUpdate > 300) { output.fd.sync(); progress(offset); lastUpdate = now }
                            }
                            output.fd.sync()
                        }
                        require(offset == expectedBytes) { "The model download ended early. Resume to continue." }
                    }
                }
            }
            redirect?.let { url = it }
            if (redirect == null && !restart) return
        }
        error("The model download redirected too many times.")
    }

    private fun allowed(url: String): Boolean {
        val uri = URI(url)
        val host = uri.host ?: return false
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) && (host in setOf("huggingface.co", "raw.githubusercontent.com", "cdn-lfs.huggingface.co") || host.endsWith(".hf.co"))
    }
}
