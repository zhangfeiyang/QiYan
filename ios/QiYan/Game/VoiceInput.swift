import AVFoundation
import Speech
import SwiftUI

/// 语音走子: 麦克风 -> 中文文本 (zh-CN, 能在本机识别就在本机) -> VoiceCommands 解析.
@MainActor
final class VoiceInput: ObservableObject {
    @Published private(set) var listening = false
    /// 最近一次识别出的整句
    @Published private(set) var recognized: String?

    // 识别器和录音引擎都等用户点了麦克风才建: 一建 SFSpeechRecognizer 就会去问语音服务 (国行会弹
    // "允许使用无线数据"), 一碰 AVAudioEngine.inputNode 就会弹麦克风权限 —— 进出对局页都不该发生
    private var recognizer: SFSpeechRecognizer?
    private var audio: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var stopWork: DispatchWorkItem?

    func start(_ message: @escaping (String) -> Void) {
        if listening { cancel(); return }
        if recognizer == nil { recognizer = SFSpeechRecognizer(locale: Locale(identifier: "zh-CN")) }
        guard let recognizer, recognizer.isAvailable else { message(L("voice_unavailable")); return }
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async {
                guard status == .authorized else { message(L("voice_permission_denied")); return }
                AVAudioApplication.requestRecordPermission { granted in
                    DispatchQueue.main.async {
                        guard granted else { message(L("voice_permission_denied")); return }
                        message(L("voice_listening"))
                        self.begin(recognizer, message)
                    }
                }
            }
        }
    }

    private func begin(_ recognizer: SFSpeechRecognizer, _ message: @escaping (String) -> Void) {
        recognized = nil
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            message(L("voice_error"))
            return
        }
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = false
        if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
        request = req
        let audio = self.audio ?? AVAudioEngine()
        self.audio = audio
        let input = audio.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
            req.append(buffer)
        }
        audio.prepare()
        do { try audio.start() } catch { message(L("voice_error")); finish(); return }
        listening = true
        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self else { return }
                if let result, result.isFinal {
                    self.recognized = result.bestTranscription.formattedString
                    self.finish()
                } else if error != nil {
                    if self.listening { message(L("voice_no_speech")) }
                    self.finish()
                }
            }
        }
        // 一句着法几秒就说完; 6 秒后收尾
        let w = DispatchWorkItem { [weak self] in self?.request?.endAudio() }
        stopWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: w)
    }

    private func finish() {
        stopWork?.cancel()
        if let audio {
            audio.stop()
            audio.inputNode.removeTap(onBus: 0)
        }
        request?.endAudio()
        request = nil
        task = nil
        listening = false
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
    }

    func cancel() {
        // 从没开过麦克风 (离开对局页时最常见) 就什么都不碰
        guard listening || task != nil || request != nil else { return }
        task?.cancel()
        finish()
    }
}
