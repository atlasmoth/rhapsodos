package com.osuji.ereaderapp

import android.app.Application
import android.app.Activity
import android.os.Bundle
import com.ereader.shared.AndroidCoreFactory
import com.osuji.ereaderapp.audio.OfflineSynthesizer
import com.osuji.ereaderapp.audio.PlaybackCoordinator
import com.osuji.ereaderapp.audio.VoiceNoteCoordinator
import com.osuji.ereaderapp.audio.VoiceRecorder
import com.osuji.ereaderapp.audio.TranscriptionCoordinator
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.data.OwnedFiles
import com.osuji.ereaderapp.data.DocumentArchives
import com.osuji.ereaderapp.models.ModelManager
import com.osuji.ereaderapp.speech.OfflineSpeechRuntime
import com.osuji.ereaderapp.pdf.PdfAdapter
import com.osuji.ereaderapp.pdf.DocumentPreparation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class ReaderApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val foreground = MutableStateFlow(false)
    private var startedActivities = 0
    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++; foreground.value = true }
            override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0); foreground.value = startedActivities > 0 }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        val koin = startKoin {
            androidContext(this@ReaderApplication)
            modules(module {
                single { applicationScope }
                single { AndroidCoreFactory.open(get()) }
                single { ReaderStore(get()) }
                single { PdfAdapter(get()) }
                single { OfflineSpeechRuntime(get()) }
                single { ModelManager(get(), get(), get(), get()) }
                single { OfflineSynthesizer(get(), get()) }
                single { PlaybackCoordinator(get(), get(), get(), get()) }
                single { TranscriptionCoordinator(get(), get(), get(), get(), get()) }
                single { VoiceRecorder(get(), get()) }
                single { VoiceNoteCoordinator(get(), get(), get(), get(), get(), get()) }
                single { DocumentPreparation(get(), get(), get(), get()) }
                single { OwnedFiles(get(), get()) }
                single { DocumentArchives(get(), get()) }
            })
        }.koin
        koin.get<ModelManager>().beforeModelRemoval = { id ->
            if (koin.get<ModelManager>().state.value.selectedTranscription == id) koin.get<TranscriptionCoordinator>().pauseForModelRemoval()
            else if (koin.get<ModelManager>().state.value.selectedVoice == id) koin.get<PlaybackCoordinator>().stopAndWait()
        }
        koin.get<ModelManager>().beforeTranscriptionChange = { koin.get<TranscriptionCoordinator>().pauseForModelRemoval() }
        koin.get<ModelManager>().transcriptionSelectionAllowed = { !koin.get<PlaybackCoordinator>().capturing }
        koin.get<ModelManager>().voiceActivationBlock = {
            val audio = koin.get<PlaybackCoordinator>().state.value
            when {
                audio.capturing -> "Finish recording to continue"
                audio.noteId != null -> "Finish note playback to continue"
                else -> null
            }
        }
        koin.get<ModelManager>().activateVoice = { pack, voice, token -> koin.get<PlaybackCoordinator>().selectVoice(koin.get(), pack, voice, token) }
        applicationScope.launch {
            koin.get<PlaybackCoordinator>().state.collect { koin.get<ModelManager>().resumePendingActivation() }
        }
        koin.get<ModelManager>().catalogueWorkloadIdle = {
            val audio = koin.get<PlaybackCoordinator>().state.value
            foreground.value && !audio.playing && !audio.capturing && !audio.preparing && !koin.get<TranscriptionCoordinator>().inferenceActive.value
        }
        applicationScope.launch {
            combine(foreground, koin.get<PlaybackCoordinator>().state, koin.get<TranscriptionCoordinator>().inferenceActive) { visible, audio, inference ->
                visible && !audio.playing && !audio.capturing && !audio.preparing && !inference
            }.collect { koin.get<ModelManager>().catalogueWorkloadChanged() }
        }
        koin.get<ModelManager>().beforeVerification = {
            combine(foreground, koin.get<PlaybackCoordinator>().state) { visible, audio -> visible && !audio.playing && !audio.capturing && !audio.preparing }.first { it }
        }
        koin.get<ModelManager>().runIdleVerification = { verification ->
            coroutineScope {
                val task = async(start = CoroutineStart.LAZY) { verification() }
                val interruption = launch(start = CoroutineStart.UNDISPATCHED) {
                    combine(foreground, koin.get<PlaybackCoordinator>().state) { visible, audio -> !visible || audio.playing || audio.capturing || audio.preparing }.first { it }
                    task.cancel(CancellationException("The device check was interrupted."))
                }
                task.start()
                try { task.await() } finally { interruption.cancel() }
            }
        }
        koin.get<PdfAdapter>().beforeOcr = {
            koin.get<PlaybackCoordinator>().state.first { !it.playing && !it.capturing && !it.preparing }
        }
        koin.get<VoiceNoteCoordinator>().recoverDrafts()
        koin.get<ModelManager>().initialize()
        koin.get<TranscriptionCoordinator>().initialize()
        koin.get<DocumentPreparation>().recover()
        applicationScope.launch { runCatching { koin.get<OwnedFiles>().clean() } }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (getProcessName() != packageName) return
        if (level >= TRIM_MEMORY_RUNNING_LOW) applicationScope.launch {
            val koin = org.koin.core.context.GlobalContext.get()
            val runtime = koin.get<OfflineSpeechRuntime>()
            val playback = koin.get<PlaybackCoordinator>().state.value
            koin.get<PdfAdapter>().releaseParserWhenIdle()
            koin.get<TranscriptionCoordinator>().pauseForMemoryPressure()
            runtime.close("android-whisper-base-en")
            if (!playback.playing && !playback.capturing && !playback.preparing) {
                runtime.close("android-phonon-english")
            }
        }
    }
}
