import AVFoundation
import UIKit

/// Sound and haptics (same clips and events as the Android GameFeedback).
/// Voice clips ask the background music to duck so the voice is not drowned.
final class Feedback {
    static let shared = Feedback()

    private var players: [String: [AVAudioPlayer]] = [:]
    private let settings = AppSettings.shared
    private let light = UIImpactFeedbackGenerator(style: .light)
    private let heavy = UIImpactFeedbackGenerator(style: .heavy)
    private let notify = UINotificationFeedbackGenerator()
    private let selection = UISelectionFeedbackGenerator()

    private static let tacticCheck: [String: String] = [
        "马后炮": "sfx_tactic_mahoupao", "铁门栓": "sfx_tactic_tiemenshuan", "卧槽马": "sfx_tactic_wochaoma",
        "双车错": "sfx_tactic_shuangche", "重炮": "sfx_tactic_zhongpao", "双炮": "sfx_tactic_zhongpao",
    ]
    private static let tacticWin: [String: String] = [
        "马后炮": "sfx_win_mahoupao", "铁门栓": "sfx_win_tiemenshuan", "重炮": "sfx_win_zhongpao",
        "双炮": "sfx_win_zhongpao", "双车错": "sfx_win_shuangche", "卧槽马": "sfx_win_wochaoma",
        "挂角马": "sfx_win_guajiaoma", "白脸将": "sfx_win_bailianjiang", "天地炮": "sfx_win_tiandipao",
        "闷宫": "sfx_win_mengong", "大刀剜心": "sfx_win_dadaowanxin", "钓鱼马": "sfx_win_diaoyuma",
    ]

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
    }

    /// 预热: 首次落子前把常用音效解码好
    func preload() {
        for name in ["sfx_move", "sfx_move_opponent", "sfx_capture", "sfx_check", "sfx_game_start"] { _ = player(name) }
    }

    private func player(_ name: String) -> AVAudioPlayer? {
        if let free = players[name]?.first(where: { !$0.isPlaying }) { return free }
        guard let url = Bundle.main.url(forResource: name, withExtension: "m4a"),
              let p = try? AVAudioPlayer(contentsOf: url) else { return nil }
        p.prepareToPlay()
        players[name, default: []].append(p)
        return p
    }

    func play(_ name: String, volume: Float = 1) {
        guard settings.sound, let p = player(name) else { return }
        p.volume = volume
        p.currentTime = 0
        p.play()
    }

    private func voice(_ name: String, seconds: Double, volume: Float = 1) {
        guard settings.sound else { return }
        Bgm.shared.duck(for: seconds)
        play(name, volume: volume)
    }

    private func haptic(_ g: UIImpactFeedbackGenerator) {
        guard settings.vibrate else { return }
        g.impactOccurred()
    }

    func selectPiece() { if settings.vibrate { selection.selectionChanged() } }
    func gameStart() { voice("sfx_game_start", seconds: 2.6) }

    func move(byPlayer: Bool) {
        if byPlayer {
            play("sfx_move", volume: 0.9)
            haptic(light)
        } else {
            play("sfx_move_opponent", volume: 0.8)
        }
    }

    func capture() {
        play("sfx_capture")
        haptic(heavy)
    }

    func undo() { play("sfx_undo", volume: 0.9) }

    func check() {
        voice("sfx_check", seconds: 2.4)
        if settings.vibrate { notify.notificationOccurred(.warning) }
    }

    func tactic(_ name: String) {
        if let clip = Feedback.tacticCheck[name] { voice(clip, seconds: 2.4) } else { check() }
    }

    func checkmate() {
        voice("sfx_checkmate", seconds: 3.2)
        if settings.vibrate { notify.notificationOccurred(.success) }
    }

    func victory(tactic: String?) {
        voice(tactic.flatMap { Feedback.tacticWin[$0] } ?? "sfx_win", seconds: 4.8)
        if settings.vibrate { notify.notificationOccurred(.success) }
    }

    func defeat() {
        voice("sfx_loss", seconds: 3.6)
        if settings.vibrate { notify.notificationOccurred(.error) }
    }

    func draw() { voice("sfx_draw", seconds: 3.0, volume: 0.9) }

    func puzzleSolved() {
        voice("sfx_puzzle_solved", seconds: 2.2)
        if settings.vibrate { notify.notificationOccurred(.success) }
    }

    func puzzleFailed() { voice("sfx_puzzle_fail", seconds: 2.4) }

    func puzzleWrong() {
        play("sfx_wrong", volume: 0.9)
        haptic(light)
    }

    func campaignCleared() { voice("sfx_campaign_clear", seconds: 3.0) }
    func recognizeStart() { play("sfx_shutter") }
    func recognizeOk() { play("sfx_chime") }
    func recognizeFail() { play("sfx_wrong", volume: 0.9) }

    func liveLock() {
        play("sfx_lock", volume: 0.9)
        if settings.vibrate { selection.selectionChanged() }
    }

    /// 大师原声 (杀法宝典): 用户点了才放, 不看音效开关
    func masterVoice(_ name: String) {
        guard let p = player(name) else { return }
        Bgm.shared.duck(for: 4.8)
        p.volume = 1
        p.currentTime = 0
        p.play()
    }
}

/// Background music: original 64-second loop "松风入局". Plays on home and game screens,
/// pauses in the background, ducks under voice clips.
final class Bgm {
    static let shared = Bgm()
    private var player: AVAudioPlayer?
    private var duckUntil = Date.distantPast
    private var wanted = false

    private var volume: Float { Float(AppSettings.shared.bgmVolume) / 100 }

    func resume(preview: Bool = false) {
        wanted = true
        guard preview || AppSettings.shared.bgm, volume > 0 else { pause(); return }
        if player == nil, let url = Bundle.main.url(forResource: "bgm_ambient", withExtension: "m4a") {
            player = try? AVAudioPlayer(contentsOf: url)
            player?.numberOfLoops = -1
            player?.volume = 0
        }
        guard let p = player else { return }
        if !p.isPlaying { p.play() }
        p.setVolume(Date() < duckUntil ? volume * 0.25 : volume, fadeDuration: 1.2)
    }

    func pause() {
        wanted = false
        guard let p = player, p.isPlaying else { return }
        p.setVolume(0, fadeDuration: 0.4)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
            guard let self, !self.wanted else { return }
            self.player?.pause()
        }
    }

    func duck(for seconds: Double) {
        guard let p = player, p.isPlaying else { return }
        duckUntil = Date().addingTimeInterval(seconds)
        p.setVolume(volume * 0.25, fadeDuration: 0.2)
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [weak self] in
            guard let self, let p = self.player, p.isPlaying, Date() >= self.duckUntil else { return }
            p.setVolume(self.volume, fadeDuration: 0.8)
        }
    }

    /// 设置里改了音量/开关后调用
    func refresh() {
        if wanted { resume() }
    }
}
