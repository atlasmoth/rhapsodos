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
    @Published var selectedVoice = UserDefaults.standard.string(forKey: "voiceIdentifier") ?? ""
    @Published var pace = UserDefaults.standard.float(forKey: "speechPace") == 0 ? 0.5 : UserDefaults.standard.float(forKey: "speechPace")
    var onPassage: ((Int) -> Void)?
    var onError: ((String) -> Void)?
    var onCaptureInterrupted: (() -> Void)?
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
    private var capturePending = false
    private var captureID: UUID?

    var voices: [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("en") }.sorted {
            $0.quality.rawValue == $1.quality.rawValue ? $0.name < $1.name : $0.quality.rawValue > $1.quality.rawValue
        }
    }
    var current: Passage? { passages.indices.contains(index) ? passages[index] : nil }

    override init() {
        super.init()
        speech.delegate = self
        speech.usesApplicationAudioSession = true
        if AVSpeechSynthesisVoice(identifier: selectedVoice) == nil {
            selectedVoice = voices.first(where: { $0.language == "en-US" })?.identifier ?? voices.first?.identifier ?? ""
        }
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification,
            object: nil, queue: .main) { [weak self] notification in
            let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            Task { @MainActor in
                guard let self, type == AVAudioSession.InterruptionType.began.rawValue else { return }
                self.pause()
                if self.recording { self.onCaptureInterrupted?() }
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification,
            object: nil, queue: .main) { [weak self] notification in
            let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            Task { @MainActor in
                guard let self, reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
                self.pause()
                if self.recording { self.onCaptureInterrupted?() }
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification,
            object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.pause()
                self.stopReplay()
                self.onCaptureInterrupted?()
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
                self.onCaptureInterrupted?()
            }
        })
        configureRemoteCommands()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
        remoteTargets.forEach { $0.0.removeTarget($0.1) }
    }

    func load(_ passages: [Passage], title: String, at index: Int) {
        pause()
        self.passages = passages
        self.title = title
        self.index = min(max(0, index), max(0, passages.count - 1))
        updateNowPlaying()
    }

    func play() {
        guard !recording, !capturePending, let current else { return }
        stopReplay()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
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

    func pause() {
        utterance = nil
        speech.stopSpeaking(at: .immediate)
        playing = false
        updateNowPlaying()
    }

    func toggle() { if playing { pause() } else { play() } }

    func move(by amount: Int) {
        guard !recording, !capturePending, !passages.isEmpty else { return }
        seek(to: min(max(index + amount, 0), passages.count - 1))
    }

    func seek(to target: Int) {
        guard !recording, !capturePending, passages.indices.contains(target) else { return }
        let resume = playing
        pause()
        index = target
        onPassage?(index)
        if resume { play() }
        updateNowPlaying()
    }

    func changeVoice(_ id: String) {
        selectedVoice = id
        UserDefaults.standard.set(id, forKey: "voiceIdentifier")
        if playing { pause(); play() }
    }

    func changePace(_ value: Float) {
        pace = value
        UserDefaults.standard.set(value, forKey: "speechPace")
        if playing { pause(); play() }
    }

    func prepareCapture() async {
        capturePending = true
        pause()
        stopReplay()
        for _ in 0..<20 {
            if !speech.isSpeaking { break }
            try? await Task.sleep(for: .milliseconds(25))
        }
    }

    func endCaptureMode() { if !recording { capturePending = false } }

    func startCapture(at url: URL) async throws -> Bool {
        guard !speech.isSpeaking else { throw CaptureFailure.narrationStillStopping }
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
        sink.onFailure = { [weak self] in
            Task { @MainActor in
                guard let self, self.captureID == id else { return }
                self.onCaptureInterrupted?()
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
        replayFile = file
    }

    func stopReplay() {
        notePlayer?.stop()
        notePlayer = nil
        replayingNote = nil
        if let replayFile { try? FileManager.default.removeItem(at: replayFile) }
        replayFile = nil
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, self.utterance === utterance, self.playing else { return }
            self.utterance = nil
            if self.index + 1 < self.passages.count { self.index += 1; self.play() }
            else { self.playing = false; self.updateNowPlaying() }
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self] in self?.stopReplay() }
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
        commands.changePlaybackPositionCommand.isEnabled = false
    }

    private func updateNowPlaying() {
        guard let current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: "Page \(current.pageIndex + 1) · Passage \(index + 1)",
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue]
    }
}

enum CaptureFailure: LocalizedError {
    case narrationStillStopping
    var errorDescription: String? { "Narration is still stopping. Keep this attached draft for later, or write your thought here." }
}
