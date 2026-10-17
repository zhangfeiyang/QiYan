import SwiftUI

/// 开源许可: 棋眼 GPL-3 + 源码地址 + 各第三方组件的版权与许可全文 (GPL §6 要求随附许可文本)
struct LicensesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var shown: LicenseText?

    static let sourceURL = "https://github.com/zhangfeiyang/QiYan"

    private struct Component: Identifiable {
        let id = UUID()
        let name: String
        let detail: String
        let license: String
        let file: String
        let url: String?
    }

    private var components: [Component] {
        [
            Component(name: L("ios_licenses_app"), detail: "© 2026 Feiyang Zhang", license: "GNU GPL v3",
                      file: "GPL-3.0", url: Self.sourceURL),
            Component(name: "PikaFish", detail: "© Pikafish authors (AUTHORS)", license: "GNU GPL v3",
                      file: "GPL-3.0", url: "https://github.com/official-pikafish/Pikafish"),
            Component(name: "PikaFish NNUE", detail: "© Pikafish authors", license: "GNU GPL v3",
                      file: "GPL-3.0", url: "https://github.com/official-pikafish/Networks"),
            Component(name: "chinese-chess-recognition", detail: "© TheOne1006", license: "MIT",
                      file: "MIT-chinese-chess-recognition", url: "https://github.com/TheOne1006/chinese-chess-recognition"),
            Component(name: "ONNX Runtime", detail: "© Microsoft Corporation", license: "MIT",
                      file: "MIT-onnxruntime", url: "https://github.com/microsoft/onnxruntime"),
            Component(name: "LXGW WenKai 霞鹜文楷", detail: "© LXGW", license: "SIL Open Font License 1.1",
                      file: "OFL-1.1", url: "https://github.com/lxgw/LxgwWenKai"),
        ]
    }

    var body: some View {
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("ios_licenses_title"), onBack: { dismiss() })
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L("ios_licenses_intro")).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                            Link(Self.sourceURL, destination: URL(string: Self.sourceURL)!)
                                .font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.action)
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .card(radius: 14)
                        ForEach(components) { c in row(c) }
                    }
                    .padding(.horizontal, 16).padding(.bottom, 24)
                }
            }
        }
        .sheet(item: $shown) { t in
            NavigationStack {
                ScrollView {
                    Text(t.text).font(.system(size: 12, design: .monospaced)).foregroundStyle(Theme.textPrimary)
                        .textSelection(.enabled)
                        .padding(16)
                }
                .background(PageBackground())
                .navigationTitle(t.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("dialog_ok")) { shown = nil } } }
            }
        }
    }

    private func row(_ c: Component) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(c.name).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.textPrimary)
                Spacer()
                Text(c.license).font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.action)
            }
            Text(c.detail).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
            if let url = c.url, let u = URL(string: url) {
                Link(url.replacingOccurrences(of: "https://", with: ""), destination: u)
                    .font(.system(size: 12)).foregroundStyle(Theme.textTertiary)
            }
            Button(L("ios_licenses_full_text")) {
                let text = Bundle.main.url(forResource: c.file, withExtension: "txt")
                    .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
                shown = LicenseText(title: c.license, text: text)
            }
            .font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.action)
            .padding(.top, 2)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(radius: 14)
    }
}

private struct LicenseText: Identifiable {
    let id = UUID()
    let title: String
    let text: String
}
