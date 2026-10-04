package com.osuji.ereaderapp

import android.app.Application
import com.ereader.shared.AndroidCoreFactory
import com.osuji.ereaderapp.audio.OfflineSynthesizer
import com.osuji.ereaderapp.audio.PlaybackCoordinator
import com.osuji.ereaderapp.audio.VoiceNoteCoordinator
import com.osuji.ereaderapp.audio.VoiceRecorder
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.pdf.PdfAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class ReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val koin = startKoin {
            androidContext(this@ReaderApplication)
            modules(module {
                single { applicationScope }
                single { AndroidCoreFactory.open(get()) }
                single { ReaderStore(get()) }
                single { PdfAdapter(get()) }
                single { OfflineSynthesizer(get()) }
                single { PlaybackCoordinator(get(), get(), get(), get()) }
                single { VoiceRecorder(get(), get()) }
                single { VoiceNoteCoordinator(get(), get(), get(), get(), get()) }
            })
        }.koin
        koin.get<VoiceNoteCoordinator>().recoverDrafts()
    }
}
