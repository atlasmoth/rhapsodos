package com.osuji.ereaderapp.models

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import com.osuji.ereaderapp.data.ModelInstall
import com.osuji.ereaderapp.data.ModelPack
import com.osuji.ereaderapp.data.PackFile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext

@Serializable
data class SpeechActivationIntent(val token: String, val packId: String, val voiceId: String = "default", val generation: String? = null, val paused: Boolean = false)

data class ModelState(
    val catalog: List<ModelPack> = emptyList(), val installs: List<ModelInstall> = emptyList(),
    val selectedVoice: String = "", val voiceId: String = "default", val selectedTranscription: String = "off",
    val checking: Set<String> = emptySet(), val removing: Set<String> = emptySet(), val error: String? = null,
    val catalogStatus: ModelCatalogStatus = ModelCatalogStatus(), val refreshing: Boolean = false,
    val catalogMessage: String? = null, val changingTranscription: Boolean = false, val initializing: Boolean = true,
    val checks: Map<String, Long> = emptyMap(),
    val unavailable: Map<String, String> = emptyMap(),
    val pendingActivation: Map<String, SpeechActivationIntent> = emptyMap(),
    val activating: Set<String> = emptySet(),
    val activationMessage: Map<String, String> = emptyMap(),
)

fun supportsOfflineSpeechAbi(abis: List<String>): Boolean = abis.any { it in setOf("arm64-v8a", "x86_64") }

class ModelManager(private val context: Context, private val store: ReaderStore, val runtime: OfflineSpeechRuntime, private val scope: CoroutineScope) {
    private val preferences = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(ModelState(selectedVoice = preferences.getString("voicePack", "") ?: "", voiceId = preferences.getString("voiceId", "default") ?: "default", selectedTranscription = preferences.getString("transcriptionPack", "off") ?: "off", pendingActivation = listOf("tts", "asr").mapNotNull { capability -> preferences.getString("activation.$capability", null)?.let { value -> runCatching { capability to Json.decodeFromString<SpeechActivationIntent>(value) }.getOrNull() } }.toMap()))
    val state = mutableState.asStateFlow()
    private val ready = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val readyEvents = ready.asSharedFlow()
    var beforeModelRemoval: (suspend (String) -> Unit)? = null
    var beforeVerification: (suspend () -> Unit)? = null
    var runIdleVerification: (suspend (suspend () -> Unit) -> Unit)? = null
    var beforeTranscriptionChange: (suspend () -> Unit)? = null
    var transcriptionSelectionAllowed: () -> Boolean = { false }
    var voiceActivationBlock: () -> String? = { "Open Rhapsodos to finish setup." }
    var activateVoice: (suspend (String, String, String) -> Unit)? = null
    var catalogueWorkloadIdle: () -> Boolean = { false }
    val catalogRefreshConfigured: Boolean get() = ModelCatalogSource.httpsUrl.isNotBlank() && ModelCatalogSource.publicKeyX963.isNotBlank()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val validated = ConcurrentHashMap.newKeySet<String>()
    private val retainedVoices = ConcurrentHashMap<String, File>()
    private val activationJobs = mutableMapOf<String, Job>()
    private val downloadGenerations = mutableMapOf<String, String>()
    private val transcriptionSelection = Mutex()
    internal var downloadService: ModelDownloadService? = null
    internal fun hasDownloads() = jobs.isNotEmpty()
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 30_000; socketTimeoutMillis = 45_000; requestTimeoutMillis = 600_000 }
    }

    fun initialize() = scope.launch {
        try {
            val catalog = store.modelCatalog()
            mutableState.update { it.copy(catalog = catalog) }
            state.value.pendingActivation.filter { (_, intent) -> catalog.none { it.id == intent.packId } }.keys.forEach(::cancelActivation)
            if (state.value.selectedTranscription != "off" && catalog.none { it.id == state.value.selectedTranscription && it.capability == "asr" && it.adapter == "android-whisper-base-en" }) selectTranscription("off")
            removeRetiredPacks(catalog)
            if (catalog.none { it.id == state.value.selectedVoice && it.capability == "tts" && it.adapter == "android-phonon-english" }) clearVoiceSelection()
            val installs = store.modelInstalls()
            val catalogStatus = store.modelCatalogStatus()
            mutableState.update { it.copy(catalog = catalog, installs = installs, catalogStatus = catalogStatus, checking = installs.filter { record -> record.status == "ready" || record.installedPath.isNotBlank() }.map { record -> record.packId }.toSet()) }
            installs.filter { it.status == "ready" || it.installedPath.isNotBlank() }.forEach { install ->
                val pack = catalog.firstOrNull { it.id == install.packId }
                val verified = if (pack != null) withContext(Dispatchers.IO) {
                    try {
                        val directory = owned(install.installedPath)
                        val complete = install.status == "ready" && install.installedPath == "Models/${pack.id}/${pack.version}" && pack.files.all { matches(directory, it) }
                        val retained = pack.adapter == "android-phonon-english" && pack.files.filterNot { it.path.startsWith("voices/") }.all { matches(directory, it) }
                        require(complete || retained)
                        val reason = runtimeCompatibility(pack)
                        if (reason == null) {
                            beforeVerification?.invoke()
                            validateRuntime(pack, directory)
                            if (!complete) retainedVoices[pack.id] = directory
                            complete
                        } else {
                            mutableState.update { it.copy(unavailable = it.unavailable + (pack.id to reason)) }
                            false
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                } else false
                if (verified) { validated += install.packId; pruneVoiceVersions(pack!!) }
                else if (retainedVoices.containsKey(install.packId) && install.status == "ready" && install.installedPath == "Models/${pack!!.id}/${pack.version}") store.invalidateModel(install.packId, install.generation, "Some voice files need to be downloaded again.")
                else if (!retainedVoices.containsKey(install.packId) && install.packId !in state.value.unavailable) store.invalidateModel(install.packId, install.generation, "Installed files failed their check. Download this pack again.")
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
            if (state.value.selectedVoice.isNotBlank() && installed(state.value.selectedVoice) == null) mutableState.value = state.value.copy(error = "Your selected voice is unavailable. Choose a voice or download it again.")
            state.value.installs.filter { it.status == "ready" }.forEach { ready.emit(it.packId) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.value = state.value.copy(error = failure.message ?: "Offline downloads could not be loaded. Restart Rhapsodos to retry.") }
        finally {
            mutableState.update { it.copy(initializing = false) }
            resumePendingActivation()
        }
    }

    fun setup(packId: String, voiceId: String = "default") = scope.launch(Dispatchers.Main.immediate) {
        if (state.value.initializing || state.value.refreshing || packId in state.value.removing) return@launch
        val pack = state.value.catalog.firstOrNull { it.id == packId } ?: return@launch
        val generation = downloadGenerations[packId] ?: state.value.installs.firstOrNull { it.packId == packId && it.status == "ready" }?.generation
        try {
            require(pack.capability != "tts" || voiceId in catalogVoiceIds(pack)) { "This voice is not available in this pack." }
            cancelActivation(pack.capability)
            val intent = SpeechActivationIntent(UUID.randomUUID().toString(), packId, voiceId, generation = generation)
            saveActivation(pack.capability, intent)
            mutableState.update { it.copy(error = null) }
            if (complete(packId)) resumePendingActivation()
            else {
                if (state.value.installs.any { it.packId == packId && it.status in setOf("failed", "paused") }) jobs[packId]?.join()
                if (activationIsCurrent(pack.capability, intent.token)) download(packId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { reportError(failure.message ?: "Setup could not be saved. Try again.") }
    }

    fun activationIsCurrent(capability: String, token: String): Boolean = state.value.pendingActivation[capability]?.token == token

    private fun saveActivation(capability: String, intent: SpeechActivationIntent?) {
        val editor = preferences.edit()
        if (intent == null) editor.remove("activation.$capability") else editor.putString("activation.$capability", Json.encodeToString(intent))
        check(editor.commit()) { "Setup could not be saved. Free storage and try again." }
        mutableState.update { it.copy(pendingActivation = if (intent == null) it.pendingActivation - capability else it.pendingActivation + (capability to intent), activationMessage = it.activationMessage - capability) }
    }

    fun cancelActivation(capability: String) {
        saveActivation(capability, null)
        activationJobs.remove(capability)?.cancel()
        mutableState.update { it.copy(activating = it.activating - capability) }
    }

    fun disableTranscription() {
        try { cancelActivation("asr") }
        catch (failure: Exception) { reportError(failure.message ?: "Transcription could not be turned off."); return }
        scope.launch(Dispatchers.Main.immediate) {
            runCatching { selectTranscription("off") }.onFailure { reportError(it.message ?: "Transcription could not be turned off.") }
        }
    }

    fun resumePendingActivation() {
        if (state.value.initializing || state.value.refreshing) return
        for ((capability, intent) in state.value.pendingActivation) {
            if (activationJobs[capability]?.isActive == true || intent.paused) continue
            val install = state.value.installs.firstOrNull { it.packId == intent.packId && it.status == "ready" && it.generation == intent.generation }
            if (install == null || !complete(intent.packId)) continue
            val blocked = if (capability == "tts") voiceActivationBlock() else if (!transcriptionSelectionAllowed()) "Finish recording to continue" else null
            if (blocked != null) {
                mutableState.update { it.copy(activationMessage = it.activationMessage + (capability to blocked)) }
                continue
            }
            val task = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
                mutableState.update { it.copy(activating = it.activating + capability, activationMessage = it.activationMessage - capability) }
                try {
                    if (capability == "tts") checkNotNull(activateVoice).invoke(intent.packId, intent.voiceId, intent.token)
                    else selectTranscription(intent.packId, intent.token)
                    val selected = if (capability == "tts") state.value.selectedVoice == intent.packId && state.value.voiceId == intent.voiceId else state.value.selectedTranscription == intent.packId
                    if (selected && activationIsCurrent(capability, intent.token)) {
                        saveActivation(capability, null)
                        if (capability == "tts") state.value.catalog.firstOrNull { it.id == intent.packId }?.let { pruneVoiceVersions(it) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (activationIsCurrent(capability, intent.token)) mutableState.update { it.copy(activationMessage = it.activationMessage + (capability to (failure.message ?: "Setup could not finish. Try again."))) }
                } finally {
                    if (activationJobs[capability] == coroutineContext[Job]) {
                        activationJobs.remove(capability)
                        mutableState.update { it.copy(activating = it.activating - capability) }
                    }
                }
            }
            activationJobs[capability] = task
            task.start()
        }
    }

    private suspend fun removeRetiredPacks(catalog: List<ModelPack>) {
        for (install in store.modelInstalls().filter { saved -> catalog.none { it.id == saved.packId } }) {
            try {
                val packId = install.packId
                require(packId.matches(Regex("[a-z0-9-]+")))
                pause(packId)
                jobs[packId]?.join()
                beforeModelRemoval?.invoke(packId)
                validated -= packId
                retainedVoices.remove(packId)
                withContext(Dispatchers.IO) {
                    listOf(owned("Models/$packId"), owned("Models/.staging/$packId")).forEach { directory ->
                        check(!directory.exists() || directory.deleteRecursively())
                        if (directory.parentFile!!.isDirectory) syncDirectory(directory.parentFile!!, context.filesDir)
                    }
                }
                store.removeModel(packId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { throw IllegalStateException("Retired model files could not be removed. Restart Rhapsodos to retry.", failure) }
        }
    }

    fun compatibility(pack: ModelPack): String? {
        runtimeCompatibility(pack)?.let { return it }
        if (StatFs(context.filesDir.absolutePath).availableBytes < pack.totalBytes + 32L * 1024 * 1024) return "Free more storage before downloading"
        return null
    }

    private fun runtimeCompatibility(pack: ModelPack): String? {
        if (pack.adapter !in setOf("android-phonon-english", "android-whisper-base-en")) return "This model needs an app update before it can run."
        if (!android.os.Process.is64Bit() || !supportsOfflineSpeechAbi(Build.SUPPORTED_ABIS.toList())) return "This speech pack requires a supported 64-bit device. You can still read PDFs and save voice notes."
        if (Build.VERSION.SDK_INT < pack.minimumOS) return "Requires Android API ${pack.minimumOS} or newer"
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (memory.totalMem / (1024 * 1024) < pack.minimumMemoryMB) return "Requires ${pack.minimumMemoryMB} MB of device memory"
        return null
    }

    fun complete(packId: String): Boolean = state.value.installs.any { it.packId == packId && it.status == "ready" && packId in validated && packId !in state.value.removing }

    fun installed(packId: String): File? {
        if (packId in state.value.removing) return null
        val directory = if (complete(packId)) state.value.installs.firstOrNull { it.packId == packId && it.installedPath.isNotBlank() }?.let { owned(it.installedPath) }
        else retainedVoices[packId]
        return directory?.takeIf { it.isDirectory }
    }

    fun downloadBytes(pack: ModelPack): Long = if (retainedVoices.containsKey(pack.id)) pack.files.filter { it.path.startsWith("voices/") }.sumOf { it.bytes } else pack.totalBytes

    fun selectedVoicePack(): ModelPack {
        check(state.value.selectedVoice.isNotBlank()) { "Download and select Phonon in Settings to start listening." }
        return state.value.catalog.firstOrNull {
            it.id == state.value.selectedVoice && it.capability == "tts" && it.adapter == "android-phonon-english" && installed(it.id) != null && state.value.voiceId in voiceIds(it.id)
        } ?: error("The selected voice is unavailable. Download and check it in Settings before listening.")
    }

    private fun catalogVoiceIds(pack: ModelPack): List<String> = pack.voices.map { it.id }.ifEmpty { listOf("default") }

    fun voiceIds(packId: String): List<String> {
        val pack = state.value.catalog.firstOrNull { it.id == packId && it.adapter == "android-phonon-english" } ?: return emptyList()
        return when { complete(packId) -> catalogVoiceIds(pack); retainedVoices.containsKey(packId) -> listOf("default"); else -> emptyList() }
    }

    fun voiceName(packId: String, voiceId: String): String = state.value.catalog.firstOrNull { it.id == packId }?.voices?.firstOrNull { it.id == voiceId }?.name ?: if (voiceId == "default") "Phonon" else voiceId
    fun asrDirectory(): File? = if (asrAdapter() == null) null else installed(state.value.selectedTranscription)
    fun asrAdapter(): String? = state.value.catalog.firstOrNull { it.id == state.value.selectedTranscription && it.capability == "asr" && it.adapter == "android-whisper-base-en" }?.adapter

    suspend fun selectTranscription(packId: String, activationToken: String? = null) {
        withContext(Dispatchers.Main.immediate) {
            if (activationToken == null) cancelActivation("asr")
            transcriptionSelection.withLock {
                fun requested() = activationToken == null || activationIsCurrent("asr", activationToken)
                check(requested()) { "Setup was cancelled." }
                check(packId == "off" || transcriptionSelectionAllowed()) { "Finish recording to continue" }
                check(packId == "off" || state.value.catalog.any { it.id == packId && it.capability == "asr" && it.adapter == "android-whisper-base-en" } && installed(packId) != null) { "Download and check this transcription pack first." }
                if (state.value.selectedTranscription == packId) return@withLock
                mutableState.update { it.copy(changingTranscription = true) }
                try {
                    beforeTranscriptionChange?.invoke()
                    coroutineContext.ensureActive()
                    check(requested()) { "Setup was cancelled." }
                    check(packId == "off" || transcriptionSelectionAllowed()) { "Finish recording to continue" }
                    check(packId == "off" || installed(packId) != null) { "This transcription pack is no longer available." }
                    check(preferences.edit().putString("transcriptionPack", packId).commit()) { "Transcription choice could not be saved." }
                    mutableState.update { it.copy(selectedTranscription = packId) }
                } finally {
                    mutableState.update { it.copy(changingTranscription = false) }
                    val selected = state.value.selectedTranscription
                    if (selected != "off" && installed(selected) != null) ready.emit(selected)
                }
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

    fun selectVoice(packId: String, voiceId: String? = null, activationToken: String? = null) {
        if (activationToken == null) cancelActivation("tts")
        else check(activationIsCurrent("tts", activationToken)) { "Setup was cancelled." }
        require(packId !in state.value.removing) { "Wait for this download to finish being removed." }
        require(installed(packId) != null) { "Download and verify this voice first." }
        require(state.value.catalog.any { it.id == packId && it.capability == "tts" && it.adapter == "android-phonon-english" })
        val voices = voiceIds(packId)
        val selected = voiceId ?: state.value.voiceId.takeIf { it in voices } ?: voices.firstOrNull()
        require(selected != null && selected in voices) { "This voice is not available in this pack." }
        check(preferences.edit().putString("voicePack", packId).putString("voiceId", selected).commit()) { "Voice choice could not be saved." }
        mutableState.update { it.copy(selectedVoice = packId, voiceId = selected) }
    }

    private fun clearVoiceSelection() {
        preferences.edit().remove("voicePack").remove("voiceId").apply()
        mutableState.update { it.copy(selectedVoice = "", voiceId = "default") }
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
        val reuse = installed(pack.id) ?: state.value.installs.firstOrNull { it.packId == pack.id && it.installedPath.isNotBlank() }?.let { owned(it.installedPath) }
        val generation = UUID.randomUUID().toString()
        state.value.pendingActivation[pack.capability]?.takeIf { it.packId == packId }?.let { saveActivation(pack.capability, it.copy(generation = generation, paused = false)) }
        try { context.startForegroundService(Intent(context, ModelDownloadService::class.java)) }
        catch (failure: Exception) { reportError("Open Rhapsodos to start this download, then try again."); return }
        mutableState.update { it.copy(error = null) }
        val task = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val staging = owned("Models/.staging/${pack.id}/${pack.version}")
            try {
                store.beginModelInstall(pack.id, generation)
                check(staging.mkdirs() || staging.isDirectory)
                var previousBytes = 0L
                for (entry in pack.files) {
                    val target = assetFile(staging, entry.path)
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    if (!matches(staging, entry) && reuse != null && matches(reuse, entry)) {
                        assetFile(reuse, entry.path).inputStream().use { input ->
                            java.io.FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
                        }
                    }
                    if (!matches(staging, entry)) {
                        stream(entry.url, target, entry.bytes) { count -> update(pack.id, generation, "downloading", previousBytes + count) }
                    }
                    require(target.length() == entry.bytes && digest(target) == entry.sha256) { "Downloaded file failed its integrity check. Retry to download it again." }
                    syncDirectory(target.parentFile!!, context.filesDir)
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
                retainedVoices.remove(pack.id)
                ready.emit(pack.id)
                withContext(Dispatchers.Main.immediate) { resumePendingActivation() }
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
                    if (downloadGenerations[pack.id] == generation) downloadGenerations.remove(pack.id)
                    if (jobs.isEmpty()) downloadService?.finishIfIdle()
                }
            }
        }
        jobs[packId] = task
        downloadGenerations[packId] = generation
        task.start()
    }

    fun reportError(error: String) { mutableState.update { it.copy(error = error) } }

    fun pause(packId: String) {
        try { state.value.pendingActivation.entries.firstOrNull { it.value.packId == packId }?.let { saveActivation(it.key, it.value.copy(paused = true)) } }
        catch (failure: Exception) { reportError(failure.message ?: "The paused choice could not be saved.") }
        jobs[packId]?.cancel()
    }
    fun pauseAll() { jobs.keys.toList().forEach(::pause) }
    fun remove(packId: String) = scope.launch(Dispatchers.Main.immediate) {
        try { state.value.pendingActivation.filterValues { it.packId == packId }.keys.forEach(::cancelActivation) }
        catch (failure: Exception) { reportError(failure.message ?: "Removal could not be saved. Try again."); return@launch }
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
            retainedVoices.remove(packId)
            withContext(Dispatchers.IO) {
                listOf(owned("Models/$packId"), owned("Models/.staging/$packId")).forEach { directory ->
                    check(!directory.exists() || directory.deleteRecursively()) { "Downloaded files could not be removed. Free storage and retry removal." }
                    if (directory.parentFile!!.isDirectory) syncDirectory(directory.parentFile!!, context.filesDir)
                }
            }
            store.removeModel(packId)
            if (state.value.selectedVoice == packId) clearVoiceSelection()
            if (state.value.selectedTranscription == packId) selectTranscription("off")
            refresh()
        } catch (failure: Exception) {
            withContext(kotlinx.coroutines.NonCancellable) {
                validated -= packId
                retainedVoices.remove(packId)
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
    private suspend fun pruneVoiceVersions(pack: ModelPack) {
        if (pack.adapter != "android-phonon-english") return
        try {
            withContext(Dispatchers.IO) {
                val current = owned("Models/${pack.id}/${pack.version}")
                val root = current.parentFile!!
                for (entry in root.listFiles().orEmpty()) {
                    require(entry.canonicalFile.parentFile == root.canonicalFile) { "The previous voice folder is unsafe." }
                    if (entry != current) check(entry.deleteRecursively()) { "Previous voice files could not be removed." }
                }
                syncDirectory(root, context.filesDir)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reportError("Previous voice files could not be removed. Restart Rhapsodos to retry cleanup.") }
    }

    private fun assetFile(directory: File, path: String): File {
        val parts = path.split('/')
        require(parts.isNotEmpty() && parts.all { it.isNotBlank() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_. -]+")) }) { "This model contains an unsupported file path." }
        val root = directory.canonicalFile
        val result = File(root, path)
        require(result.canonicalPath == result.absolutePath && result.canonicalPath.startsWith(root.path + File.separator)) { "This model contains an unsafe file path." }
        return result
    }

    private fun matches(directory: File, entry: PackFile): Boolean = assetFile(directory, entry.path).let { it.length() == entry.bytes && digest(it) == entry.sha256 }

    private fun digest(file: File): String {
        if (!file.isFile) return ""
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun validateRuntime(pack: ModelPack, directory: File) {
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
                    beforeVerification?.invoke()
                }
            }
        }
        catch (failure: LinkageError) { throw IllegalStateException("This device cannot load this offline pack. Remove the download and try again.", failure) }
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
