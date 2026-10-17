import SwiftUI

/// 设置 (port of SettingsActivity): 界面与声音、棋盘材质、棋子字符、记谱、主题、引擎、清除数据
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var router: Router
    @ObservedObject private var settings = AppSettings.shared
    @State private var previewing = false
    @State private var previewTask: DispatchWorkItem?
    @State private var confirmClear = false
    @State private var showAbout = false
    @State private var threads = Double(AppSettings.shared.threads)
    @State private var hash = Double(AppSettings.shared.hashMb)
    @State private var volume = Double(AppSettings.shared.bgmVolume)
    private let preview = BoardHandle()

    var body: some View {
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("title_settings"), onBack: { dismiss() })
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        label("settings_section_display")
                        VStack(alignment: .leading, spacing: 0) {
                            toggle("settings_sound", "settings_desc_sound", $settings.sound)
                            toggle("settings_bgm", "audio_music_description", Binding(
                                get: { settings.bgm },
                                set: { settings.bgm = $0; if !$0 { stopPreview() } }))
                            music
                            toggle("settings_vibrate", "settings_desc_vibrate", $settings.vibrate)
                            toggle("settings_show_coords", "settings_desc_coords", $settings.showCoordinates)
                            toggle("settings_show_winrate", "settings_desc_winrate", $settings.showWinRate)
                            toggle("settings_animation", "audio_motion_description", $settings.animation)
                            toggle("settings_capture_fx", "settings_desc_capture_fx", $settings.captureFx)
                            skins
                            toggle("settings_board_flip", "settings_desc_flip", $settings.boardFlipped)
                        }
                        .padding(.horizontal, 20).padding(.vertical, 10)
                        .card()

                        label("settings_piece_set")
                        desc("settings_piece_set_desc").padding(.bottom, 10)
                        radios([(0, "settings_follow_language"), (1, "piece_set_hanzi"), (2, "piece_set_latin")],
                               $settings.pieceSetMode)

                        label("settings_notation")
                        desc("settings_notation_desc").padding(.bottom, 10)
                        radios([(0, "settings_follow_language"), (1, "notation_chinese"), (2, "notation_wxf")],
                               $settings.notationMode)

                        label("settings_theme")
                        radios([(2, "settings_theme_light"), (1, "settings_theme_dark"), (0, "settings_theme_system")],
                               $settings.themeMode)

                        label("settings_engine_advanced")
                        engine

                        label("settings_section_other")
                        Button(L("menu_about")) { showAbout = true }.buttonStyle(OutlineButtonStyle(fullWidth: true))
                        Button(L("ios_licenses_title")) { router.go(.licenses) }
                            .buttonStyle(OutlineButtonStyle(fullWidth: true)).padding(.top, 10)
                        Button(L("settings_clear_data")) { confirmClear = true }
                            .buttonStyle(OutlineButtonStyle(color: Theme.danger, fullWidth: true)).padding(.top, 10)
                        desc("settings_clear_data_desc").padding(.top, 8)
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 32)
                }
            }
        }
        .onAppear {
            let p = Position()
            p.setupStartPosition()
            preview.setPosition(p)
        }
        .onDisappear { stopPreview() }
        .alert(L("menu_about"), isPresented: $showAbout) {
            Button(L("dialog_ok"), role: .cancel) {}
        } message: { Text(AboutText.full) }
        .alert(L("dialog_clear_data_title"), isPresented: $confirmClear) {
            Button(L("dialog_yes"), role: .destructive) { clearAll() }
            Button(L("dialog_no"), role: .cancel) {}
        } message: { Text(L("dialog_clear_data_message")) }
    }

    // MARK: Pieces

    private func label(_ key: String) -> some View {
        Text(L(key)).font(.system(size: 14)).foregroundStyle(Theme.textTertiary)
            .padding(.top, 24).padding(.bottom, 10).padding(.leading, 2)
    }

    private func desc(_ key: String) -> some View {
        Text(L(key)).font(.system(size: 14)).foregroundStyle(Theme.textSecondary).lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func toggle(_ title: String, _ detail: String, _ value: Binding<Bool>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: value) {
                Text(L(title)).font(.system(size: 17)).foregroundStyle(Theme.textPrimary)
            }
            .tint(Theme.action)
            desc(detail)
        }
        .padding(.vertical, 10)
    }

    private var music: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L("audio_music_volume")).font(.system(size: 17)).foregroundStyle(Theme.textPrimary)
                Spacer()
                Text(L("audio_volume_value", Int(volume))).font(.system(size: 15)).foregroundStyle(Theme.action)
            }
            Slider(value: $volume, in: 0...100, step: 1) { _ in }
                .tint(Theme.action)
                .disabled(!settings.bgm)
                .onChange(of: volume) { _, v in
                    settings.bgmVolume = Int(v)
                    if v == 0 { stopPreview() } else if previewing { Bgm.shared.resume(preview: true) }
                }
                .accessibilityLabel(L("audio_music_volume"))
            Button(L(previewing ? "audio_preview_stop" : "audio_preview")) {
                previewing ? stopPreview() : startPreview()
            }
            .buttonStyle(OutlineButtonStyle(height: 46, fullWidth: true))
            .disabled(!settings.bgm || volume == 0)
            .opacity(!settings.bgm || volume == 0 ? 0.5 : 1)
        }
        .padding(.vertical, 10)
    }

    private var skins: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("settings_board_skin")).font(.system(size: 17)).foregroundStyle(Theme.textPrimary)
            desc("settings_board_skin_desc")
            BoardView(handle: preview, inputEnabled: false)
                .frame(height: 280)
                .padding(.top, 6)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(BoardSkin.allCases) { s in
                    radioRow(L(s.nameKey), on: settings.boardSkin == s.rawValue) { settings.boardSkin = s.rawValue }
                }
            }
        }
        .padding(.vertical, 10)
    }

    private func radios(_ items: [(Int, String)], _ value: Binding<Int>) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(items, id: \.0) { item in
                radioRow(L(item.1), on: value.wrappedValue == item.0) { value.wrappedValue = item.0 }
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func radioRow(_ title: String, on: Bool, _ pick: @escaping () -> Void) -> some View {
        Button(action: pick) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().stroke(on ? Theme.action : Theme.textTertiary, lineWidth: 2).frame(width: 22, height: 22)
                    if on { Circle().fill(Theme.action).frame(width: 12, height: 12) }
                }
                Text(title).font(.system(size: 17)).foregroundStyle(Theme.textPrimary)
                Spacer(minLength: 0)
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private var engine: some View {
        VStack(alignment: .leading, spacing: 0) {
            slider("settings_threads", "settings_desc_threads", value: $threads, range: 1...8, step: 1,
                   text: "\(Int(threads))") { settings.threads = Int($0) }
            Rectangle().fill(Theme.borderSubtle).frame(height: 1).padding(.vertical, 12)
            slider("settings_hash", "settings_desc_hash", value: $hash, range: 16...1024, step: 16,
                   text: "\(Int(hash)) MB") { settings.hashMb = Int($0) }
        }
        .padding(20)
        .card()
    }

    private func slider(_ title: String, _ detail: String, value: Binding<Double>, range: ClosedRange<Double>,
                        step: Double, text: String, _ commit: @escaping (Double) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L(title)).font(.system(size: 17)).foregroundStyle(Theme.textPrimary)
                    desc(detail)
                }
                Spacer(minLength: 12)
                Text(text).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.action)
            }
            Slider(value: value, in: range, step: step).tint(Theme.action)
                .onChange(of: value.wrappedValue) { _, v in commit(v) }
                .accessibilityLabel(L(title))
        }
    }

    // MARK: Actions

    private func startPreview() {
        previewing = true
        Bgm.shared.resume(preview: true)
        let task = DispatchWorkItem { stopPreview() }
        previewTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + 12, execute: task)
    }

    private func stopPreview() {
        previewTask?.cancel()
        previewTask = nil
        if previewing { Bgm.shared.pause() }
        previewing = false
    }

    private func clearAll() {
        stopPreview()
        settings.clearAll()
        GameStore.shared.clear()
        UserProfile.shared.objectWillChange.send()
        ProgressStore.shared.objectWillChange.send()
        threads = Double(settings.threads)
        hash = Double(settings.hashMb)
        volume = Double(settings.bgmVolume)
    }
}

/// 关于: 安卓原文 + iOS 版的开源与本机运行说明
enum AboutText {
    static var full: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        return L("settings_about_text").replacingOccurrences(of: "v1.0", with: "v\(v)") + "\n\n" + L("ios_about_engine")
    }
}
