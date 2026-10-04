import AVFoundation
import MediaPlayer
import SwiftUI

@MainActor
final class AudioCoordinator: NSObject, ObservableObject, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate {
    @Published private(set) var playing = false
    @Published private(set) var index = 0
    @Published private(set) var recording = false
    @Published private(set) var level: Float = 0
    @Published private(set) var recordingSeconds = 0.0
    @Published private(set) var replayingNote: String?
    @Published private(set) var replayPaused = false
    @Published private(set) var sleepUntil: Date?
    @Published private(set) var spokenRange: NSRange?
    @Published private(set) var generating = false
    @Published private(set) var elapsed = 0.0
    @Published private(set) var duration = 0.0
    @Published var selectedVoice = UserDefaults.standard.string(forKey: "voiceIdentifier") ?? ""
    @Published private(set) var previewing = false
    @Published var pace = UserDefaults.standard.float(forKey: "speechPace") == 0 ? 0.5 : UserDefaults.standard.float(forKey: "speechPace")
    var onPassage: ((Int) -> Void)?
    var onPosition: ((Int, Int64) -> Void)?
    var renderVoice: ((Passage, String, Float) async throws -> URL)?
    var onError: ((String) -> Void)?
    var onCaptureInterrupted: ((String?) -> Void)?
    var passages: [Passage] = []
    var title = ""
    private var speech = AVSpeechSynthesizer()
    private var utterance: AVSpeechUtterance?
    private var engine: AVAudioEngine?
    private var writer: CaptureWriter?
    private var timer: Timer?
    private var notePlayer: AVAudioPlayer?
    private var replayFile: URL?
    private var observers: [NSObjectProtocol] = []
    private var remoteTargets: [(MPRemoteCommand, Any)] = []
    private(set) var capturePending = false
    private var captureID: UUID?
    private var sleepTimer: Timer?
    private var narrationPlayer: AVAudioPlayer?
    private var narrationTask: Task<Void, Never>?
    private var narrationGeneration: UUID?
    private var preloadTask: Task<Void, Never>?
    private var preloadID: UUID?
    private var voiceChangeID: UUID?
    private var playbackTimer: Timer?
    private var pendingOffset = 0.0
    private var previewPlayer: AVAudioPlayer?
    private var previewSpeech: AVSpeechSynthesizer?
    private var previewUtterance: AVSpeechUtterance?
    private var previewTask: Task<Void, Never>?
    private var previewTaskID: UUID?
    private var previewGeneration: UUID?

    var voices: [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("en") }.sorted {
            $0.quality.rawValue == $1.quality.rawValue ? $0.name < $1.name : $0.quality.rawValue > $1.quality.rawValue
        }
    }
    var current: Passage? { passages.indices.contains(index) ? passages[index] : nil }
    func updatePassages(_ passages: [Passage]) { self.passages = passages; updateNowPlaying() }

    override init() {
        super.init()
        speech.delegate = self
        speech.usesApplicationAudioSession = true
        if !selectedVoice.hasPrefix("kokoro:"), AVSpeechSynthesisVoice(identifier: selectedVoice) == nil {
            selectedVoice = voices.first(where: { $0.language == "en-US" })?.identifier ?? voices.first?.identifier ?? ""
        }
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification,
            object: nil, queue: .main) { [weak self] notification in
            let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            Task { @MainActor in
                guard let self, type == AVAudioSession.InterruptionType.began.rawValue else { return }
                self.pause()
                if self.recording { self.onCaptureInterrupted?(nil) }
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification,
            object: nil, queue: .main) { [weak self] notification in
            let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            Task { @MainActor in
                guard let self, reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
                self.pause()
                if self.recording { self.onCaptureInterrupted?(nil) }
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification,
            object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.pause()
                self.stopReplay()
                self.onCaptureInterrupted?(nil)
                self.speech = AVSpeechSynthesizer()
                self.speech.delegate = self
                self.speech.usesApplicationAudioSession = true
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange,
            object: nil, queue: .main) { [weak self] notification in
            let changed = notification.object as? AVAudioEngine
            Task { @MainActor in
                guard let self, self.recording, self.engine === changed else { return }
                self.onCaptureInterrupted?(nil)
            }
        })
        configureRemoteCommands()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        remoteTargets.forEach { $0.0.removeTarget($0.1) }
    }

    func load(_ passages: [Passage], title: String, at index: Int, offsetMillis: Int64 = 0) {
        pause(restart: true)
        stopReplay()
        self.passages = passages
        self.title = title
        self.index = min(max(0, index), max(0, passages.count - 1))
        pendingOffset = Double(max(0, offsetMillis)) / 1000
        updateNowPlaying()
    }

    func play() {
        guard !recording, !capturePending, let current else { return }
        if let sleepUntil, Date() >= sleepUntil { setSleepTimer(minutes: nil); pause(); return }
        stopReplay()
        stopPreview()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
            if selectedVoice.hasPrefix("kokoro:") {
                if let narrationPlayer {
                    guard narrationPlayer.play() else { throw VaultFailure.noAudio }
                    playing = true; startPlaybackClock(); preloadNextPassage(); updateNowPlaying(); return
                }
                guard !generating, let renderVoice else { return }
                let generation = UUID(); narrationGeneration = generation; generating = true; playing = true
                let voice = String(selectedVoice.dropFirst(7)), pace = self.pace
                narrationTask = Task(priority: .utility) { [weak self] in
                    defer { if self?.narrationGeneration == generation { self?.narrationTask = nil } }
                    do {
                        let file = try await renderVoice(current, voice, pace)
                        try Task.checkCancellation()
                        guard let self, self.narrationGeneration == generation, self.playing, !self.capturePending else { return }
                        let player = try AVAudioPlayer(contentsOf: file)
                        player.delegate = self
                        player.currentTime = min(self.pendingOffset, max(0, player.duration - 0.05)); self.pendingOffset = 0
                        guard player.play() else { throw VaultFailure.noAudio }
                        self.narrationPlayer = player; self.duration = player.duration; self.elapsed = player.currentTime
                        self.generating = false; self.onPassage?(self.index); self.startPlaybackClock(); self.preloadNextPassage(); self.updateNowPlaying()
                    } catch is CancellationError {
                    } catch {
                        guard let self, self.narrationGeneration == generation else { return }
                        self.generating = false; self.playing = false; self.onError?(error.localizedDescription); self.updateNowPlaying()
                    }
                }
                updateNowPlaying(); return
            }
            if speech.isPaused, utterance != nil, speech.continueSpeaking() {
                playing = true; updateNowPlaying(); return
            }
            let item = AVSpeechUtterance(string: current.spokenText)
            item.voice = AVSpeechSynthesisVoice(identifier: selectedVoice) ?? AVSpeechSynthesisVoice(language: "en-US")
            item.rate = pace
            item.postUtteranceDelay = 0.12
            utterance = item
            playing = true
            speech.speak(item)
            onPassage?(index)
            updateNowPlaying()
        } catch { playing = false; onError?(error.localizedDescription) }
    }

    func pause(restart: Bool = false) {
        let wasPlaying = playing
        pauseReplay()
        preloadTask?.cancel()
        stopPreview()
        narrationTask?.cancel(); narrationTask = nil; narrationGeneration = nil; generating = false
        if let narrationPlayer { narrationPlayer.pause(); elapsed = narrationPlayer.currentTime; if wasPlaying { onPosition?(index, Int64(elapsed * 1000)) } }
        playbackTimer?.invalidate(); playbackTimer = nil
        if restart {
            narrationPlayer?.stop(); narrationPlayer = nil; duration = 0; elapsed = 0; pendingOffset = 0
            utterance = nil
            spokenRange = nil
            speech.stopSpeaking(at: .immediate)
        } else if speech.isSpeaking && !speech.isPaused { _ = speech.pauseSpeaking(at: .immediate) }
        playing = false
        updateNowPlaying()
    }

    func setSleepTimer(minutes: Int?) {
        sleepTimer?.invalidate(); sleepTimer = nil
        sleepUntil = minutes.map { Date().addingTimeInterval(Double($0) * 60) }
        guard let end = sleepUntil else { return }
        sleepTimer = Timer(fire: end, interval: 0, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.pause(); self?.setSleepTimer(minutes: nil) }
        }
        RunLoop.main.add(sleepTimer!, forMode: .common)
    }

    func toggle() { if playing { pause() } else { play() } }

    func move(by amount: Int) {
        guard !recording, !capturePending, !passages.isEmpty else { return }
        seek(to: min(max(index + amount, 0), passages.count - 1))
    }

    func seek(to target: Int) {
        guard !recording, !capturePending, passages.indices.contains(target) else { return }
        let resume = playing
        pause(restart: true)
        index = target
        onPassage?(index)
        if resume { play() }
        updateNowPlaying()
    }

    func changeVoice(_ id: String) async {
        let operation = UUID(); voiceChangeID = operation
        let resume = playing
        selectedVoice = id
        UserDefaults.standard.set(id, forKey: "voiceIdentifier")
        await pauseAndJoinGeneration(restart: true)
        if voiceChangeID == operation, resume { play() }
    }

    func changePace(_ value: Float) async {
        let operation = UUID(); voiceChangeID = operation
        let resume = playing
        pace = value
        UserDefaults.standard.set(value, forKey: "speechPace")
        await pauseAndJoinGeneration(restart: true)
        if voiceChangeID == operation, resume { play() }
    }

    func pauseAndJoinGeneration(restart: Bool = false) async {
        let currentTask = narrationTask, nextTask = preloadTask, sampleTask = previewTask
        pause(restart: restart)
        await currentTask?.value
        await nextTask?.value
        await sampleTask?.value
    }

    private func preloadNextPassage() {
        guard playing, selectedVoice.hasPrefix("kokoro:"), passages.indices.contains(index + 1), let renderVoice else { return }
        preloadTask?.cancel()
        let operation = UUID(); preloadID = operation
        let next = passages[index + 1], voice = String(selectedVoice.dropFirst(7)), speed = pace
        preloadTask = Task(priority: .utility) { [weak self] in
            defer { if self?.preloadID == operation { self?.preloadTask = nil; self?.preloadID = nil } }
            do { _ = try await renderVoice(next, voice, speed); try Task.checkCancellation() }
            catch { }
        }
    }

    func audition() {
        guard !recording, !capturePending else { return }
        pause()
        let generation = UUID()
        previewGeneration = generation
        previewing = true
        let text = "Listening begins here. Notes stay with the source."
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
            if selectedVoice.hasPrefix("kokoro:") {
                guard let renderVoice else { throw PackFailure.notReady }
                let voice = String(selectedVoice.dropFirst(7)), speed = pace
                let sample = Passage(id: "voice-sample", documentId: "", pageIndex: 0, pageStart: 0, pageEnd: text.utf16.count, sourceStart: 0, sourceEnd: text.utf16.count, sourceText: text, spokenText: text, displayText: text, languageTag: "en", mappingVersion: "sample-v1", spokenMapping: [], provenance: "embedded")
                previewTaskID = generation
                previewTask = Task { [weak self] in
                    defer { if self?.previewTaskID == generation { self?.previewTask = nil; self?.previewTaskID = nil } }
                    do {
                        let file = try await renderVoice(sample, voice, speed)
                        try Task.checkCancellation()
                        guard let self, self.previewGeneration == generation, self.previewing else { return }
                        let player = try AVAudioPlayer(contentsOf: file)
                        player.delegate = self
                        guard player.play() else { throw VaultFailure.noAudio }
                        self.previewPlayer = player
                    } catch is CancellationError {
                    } catch {
                        guard let self, self.previewGeneration == generation else { return }
                        self.stopPreview(); self.onError?(error.localizedDescription)
                    }
                }
            } else {
                let synthesizer = previewSpeech ?? AVSpeechSynthesizer()
                previewSpeech = synthesizer; synthesizer.delegate = self; synthesizer.usesApplicationAudioSession = true
                let item = AVSpeechUtterance(string: text)
                item.voice = AVSpeechSynthesisVoice(identifier: selectedVoice) ?? AVSpeechSynthesisVoice(language: "en-US")
                item.rate = pace
                previewUtterance = item
                synthesizer.speak(item)
            }
        } catch { stopPreview(); onError?(error.localizedDescription) }
    }

    func stopPreview() {
        previewTask?.cancel()
        previewGeneration = nil
        previewPlayer?.stop(); previewPlayer = nil
        previewUtterance = nil
        previewSpeech?.stopSpeaking(at: .immediate)
        previewing = false
    }

    struct CapturePreparation {
        fileprivate let tasks: [Task<Void, Never>]
        let offsetMillis: Int64
    }

    func reserveCapture() -> CapturePreparation {
        capturePending = true
        let tasks = [narrationTask, preloadTask, previewTask].compactMap { $0 }
        pause(restart: narrationPlayer == nil)
        stopReplay()
        return CapturePreparation(tasks: tasks, offsetMillis: offsetMillis)
    }

    func finishPreparingCapture(_ preparation: CapturePreparation) async {
        for task in preparation.tasks { await task.value }
        for _ in 0..<20 {
            if !speech.isSpeaking && previewSpeech?.isSpeaking != true { break }
            try? await Task.sleep(for: .milliseconds(25))
        }
    }

    func prepareCapture() async { await finishPreparingCapture(reserveCapture()) }

    func endCaptureMode() { if !recording { capturePending = false } }

    func startCapture(at url: URL) async throws -> Bool {
        guard !speech.isSpeaking, previewSpeech?.isSpeaking != true else { throw CaptureFailure.narrationStillStopping }
        let permitted = await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
        }
        guard permitted else { return false }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)
        let capture = AVAudioEngine()
        let input = capture.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw VaultFailure.noAudio }
        let sink = try CaptureWriter(url: url, format: format)
        let id = UUID()
        captureID = id
        sink.onFailure = { [weak self] error in
            Task { @MainActor in
                guard let self, self.captureID == id else { return }
                self.onCaptureInterrupted?(error.localizedDescription)
            }
        }
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            sink.append(buffer)
            guard let data = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return }
            var energy: Float = 0
            for i in stride(from: 0, to: Int(buffer.frameLength), by: 8) { energy += data[i] * data[i] }
            let value = min(1, sqrt(energy / Float(max(1, Int(buffer.frameLength) / 8))) * 8)
            Task { @MainActor in if self?.captureID == id { self?.level = value } }
        }
        do { try capture.start() }
        catch { captureID = nil; input.removeTap(onBus: 0); _ = try? await sink.finish(); throw error }
        writer = sink
        engine = capture
        recording = true
        recordingSeconds = 0
        timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.recordingSeconds += 0.2 }
        }
        return true
    }

    func finishCapture() async throws -> Int64 {
        captureID = nil
        timer?.invalidate()
        timer = nil
        engine?.inputNode.removeTap(onBus: 0)
        engine?.stop()
        engine = nil
        recording = false
        level = 0
        guard let sink = writer else { throw VaultFailure.noAudio }
        writer = nil
        return try await sink.finish()
    }

    func replay(noteId: String, file: URL) throws {
        pause()
        stopReplay()
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try AVAudioSession.sharedInstance().setActive(true)
        let player = try AVAudioPlayer(contentsOf: file)
        player.delegate = self
        guard player.play() else { throw VaultFailure.noAudio }
        notePlayer = player
        replayingNote = noteId
        replayPaused = false
        replayFile = file
    }

    var replayPlaying: Bool { replayingNote != nil && !replayPaused }
    var replayOffsetMillis: Int64 { notePlayer.map { Int64($0.currentTime * 1000) } ?? 0 }
    func pauseReplay() {
        guard let notePlayer else { return }
        notePlayer.pause()
        replayPaused = true
    }
    func resumeReplay() throws {
        guard let notePlayer, replayingNote != nil, !recording, !capturePending else { return }
        pause()
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try AVAudioSession.sharedInstance().setActive(true)
        guard notePlayer.play() else { throw VaultFailure.noAudio }
        replayPaused = false
    }
    func stopReplay() {
        notePlayer?.stop()
        notePlayer = nil
        replayingNote = nil
        replayPaused = false
        if let replayFile { try? FileManager.default.removeItem(at: replayFile) }
        replayFile = nil
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            if let self, self.previewUtterance === utterance { self.stopPreview(); return }
            guard let self, self.utterance === utterance, self.playing else { return }
            self.utterance = nil
            self.spokenRange = nil
            if self.index + 1 < self.passages.count { self.index += 1; self.play() }
            else { self.playing = false; self.updateNowPlaying() }
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, willSpeakRangeOfSpeechString characterRange: NSRange, utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, self.utterance === utterance, self.playing else { return }
            self.spokenRange = characterRange
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            if self.narrationPlayer === player {
                self.playbackTimer?.invalidate(); self.playbackTimer = nil
                self.narrationPlayer = nil; self.duration = 0; self.elapsed = 0
                guard flag, self.playing else { self.playing = false; self.updateNowPlaying(); return }
                if self.index + 1 < self.passages.count { self.index += 1; self.play() }
                else { self.playing = false; self.updateNowPlaying() }
            } else if self.notePlayer === player { self.stopReplay() }
            else if self.previewPlayer === player { self.stopPreview() }
        }
    }

    var offsetMillis: Int64 { narrationPlayer.map { Int64($0.currentTime * 1000) } ?? 0 }
    func seekOffset(_ offsetMillis: Int64) {
        guard !recording, !capturePending, selectedVoice.hasPrefix("kokoro:") else { return }
        if let player = narrationPlayer { player.currentTime = min(max(0, Double(offsetMillis) / 1000), player.duration); elapsed = player.currentTime; onPosition?(index, offsetMillis); updateNowPlaying() }
        else { pendingOffset = Double(max(0, offsetMillis)) / 1000 }
    }
    private func startPlaybackClock() {
        playbackTimer?.invalidate()
        var ticks = 0
        playbackTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let player = self.narrationPlayer, self.playing else { return }
                self.elapsed = player.currentTime; ticks += 1
                if ticks % 4 == 0 { self.onPosition?(self.index, Int64(player.currentTime * 1000)); self.updateNowPlaying() }
                if let until = self.sleepUntil, Date() >= until { self.pause(); self.setSleepTimer(minutes: nil) }
            }
        }
    }

    private func configureRemoteCommands() {
        let commands = MPRemoteCommandCenter.shared()
        for (command, action) in [(commands.playCommand as MPRemoteCommand, 0), (commands.pauseCommand, 1),
                                  (commands.nextTrackCommand, 2), (commands.previousTrackCommand, 3),
                                  (commands.togglePlayPauseCommand, 4)] {
            let token = command.addTarget { [weak self] _ in
                Task { @MainActor in
                    guard let self else { return }
                    switch action {
                    case 0: if !self.playing { self.play() }
                    case 1: self.pause()
                    case 2: self.move(by: 1)
                    case 3: self.move(by: -1)
                    default: self.toggle()
                    }
                }
                return .success
            }
            remoteTargets.append((command, token))
        }
        let position = commands.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            Task { @MainActor in self?.seekOffset(Int64(event.positionTime * 1000)) }
            return .success
        }
        remoteTargets.append((commands.changePlaybackPositionCommand, position))
    }

    private func updateNowPlaying() {
        guard let current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: "Page \(current.pageIndex + 1) · Passage \(index + 1)",
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue]
        MPRemoteCommandCenter.shared().changePlaybackPositionCommand.isEnabled = narrationPlayer != nil
        if narrationPlayer != nil {
            MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPMediaItemPropertyPlaybackDuration] = duration
            MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed
        }
    }
}

enum CaptureFailure: LocalizedError {
    case narrationStillStopping
    var errorDescription: String? { "Narration is still stopping. Keep this attached draft for later, or write your thought here." }
}
