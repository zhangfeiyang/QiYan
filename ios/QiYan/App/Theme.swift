import SwiftUI

/// Design tokens shared by every screen (Android dimens.xml + the color tokens in the asset catalog).
enum Theme {
    static let gutter: CGFloat = 16
    static let radiusSm: CGFloat = 6
    static let radiusMd: CGFloat = 10
    static let radiusLg: CGFloat = 14

    static func c(_ name: String) -> Color { Color(name) }

    static var textPrimary: Color { c("text_primary") }
    static var textSecondary: Color { c("text_secondary") }
    static var textTertiary: Color { c("text_tertiary") }
    static var action: Color { c("action") }
    static var affirm: Color { c("affirm") }
    static var danger: Color { c("danger") }
    static var warning: Color { c("warning") }
    static var surfaceRaised: Color { c("surface_raised") }
    static var surfaceBase: Color { c("surface_base") }
    static var surfaceSunken: Color { c("surface_sunken") }
    static var borderSubtle: Color { c("border_subtle") }
    static var borderStrong: Color { c("border_strong") }

    /// 楷体 (棋子子集只含棋子字, 标题用系统宋体近似)
    static func serif(_ size: CGFloat, _ weight: Font.Weight = .bold) -> Font {
        .system(size: size, weight: weight, design: .serif)
    }
}

/// 宣纸微渐变底 (bg_page)
struct PageBackground: View {
    var body: some View {
        LinearGradient(colors: [Theme.c("page_grad_1"), Theme.c("page_grad_2"), Theme.c("page_grad_3")],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }
}

/// 首页底: 宣纸渐变 + 竹枝 + 远山 + 一轮淡朱红日 (bg_home)
struct HomeBackground: View {
    var body: some View {
        GeometryReader { geo in
            ZStack {
                PageBackground()
                Image("ink_bamboo").resizable().frame(width: 108, height: 216)
                    .position(x: 4 + 54, y: 20 + 108)
                Image("ink_bamboo").resizable().frame(width: 108, height: 216)
                    .position(x: geo.size.width - 4 - 54, y: geo.size.height - 4 - 108)
                Image("ink_mountains").resizable().frame(width: geo.size.width, height: 120)
                    .position(x: geo.size.width / 2, y: geo.size.height - 60)
                Image("ink_sun").resizable().frame(width: 72, height: 72)
                    .position(x: geo.size.width - 28 - 36, y: 72 + 36)
            }
            .ignoresSafeArea()
        }
        .ignoresSafeArea()
    }
}

/// 鎏金主按钮: 金渐变 + 暗金描边 + 顶部白光, 深褐红字
struct GoldButtonStyle: ButtonStyle {
    var height: CGFloat = 52
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .bold))
            .foregroundStyle(Theme.c("gold_text"))
            .frame(maxWidth: .infinity, minHeight: height)
            .background(
                Capsule().fill(LinearGradient(colors: [Theme.c("gold_grad_1"), Theme.c("gold_grad_2"), Theme.c("gold_grad_3")],
                                              startPoint: .top, endPoint: .bottom))
                    .overlay(Capsule().fill(LinearGradient(colors: [.white.opacity(0.35), .white.opacity(0.03)],
                                                           startPoint: .top, endPoint: .center)).padding(1))
                    .overlay(Capsule().stroke(Theme.c("gold_stroke"), lineWidth: 1))
            )
            .opacity(configuration.isPressed ? 0.85 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
    }
}

/// 描边胶囊次按钮
struct OutlineButtonStyle: ButtonStyle {
    var color: Color = Theme.textPrimary
    var height: CGFloat = 44
    var fullWidth = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(color)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
            .padding(.horizontal, 16)
            .background(Capsule().fill(Theme.surfaceRaised))
            .overlay(Capsule().stroke(color == Theme.textPrimary ? Theme.borderStrong : color.opacity(0.6), lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// 卡片底: 米白面 + 细边
struct CardBackground: ViewModifier {
    var fill: Color = Theme.surfaceRaised
    var radius: CGFloat = 18
    func body(content: Content) -> some View {
        content
            .background(RoundedRectangle(cornerRadius: radius).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: radius).stroke(Theme.borderSubtle, lineWidth: 1))
    }
}

extension View {
    func card(fill: Color = Theme.surfaceRaised, radius: CGFloat = 18) -> some View {
        modifier(CardBackground(fill: fill, radius: radius))
    }
}

/// 小胶囊 (难度/风格/提示)
struct Pill: View {
    let text: String
    var color: Color = Theme.textSecondary
    var fill: Color = Theme.surfaceRaised
    var stroke: Color? = nil
    var trailingChevron = false
    var body: some View {
        HStack(spacing: 2) {
            Text(text).lineLimit(1)
            if trailingChevron {
                Image("ic_chevron_down").renderingMode(.template).resizable().frame(width: 14, height: 14)
                    .foregroundStyle(Theme.textTertiary)
            }
        }
        .font(.system(size: 13, weight: .semibold))
        .foregroundStyle(color)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule().fill(fill))
        .overlay(Capsule().stroke(stroke ?? .clear, lineWidth: 1.2))
    }
}

/// 顶部导航条: 返回键 + 标题 (+ 副标题) + 右侧动作
struct TopBar<Trailing: View>: View {
    let title: String
    var subtitle: String? = nil
    var close = false
    let onBack: () -> Void
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onBack) {
                Image(close ? "ic_close" : "ic_back").renderingMode(.template).resizable().frame(width: 24, height: 24)
                    .foregroundStyle(Theme.textPrimary)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(L(close ? "dialog_cancel" : "action_back"))
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.textPrimary).lineLimit(1)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(Theme.textSecondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            trailing()
        }
        .padding(.horizontal, 6)
        .frame(height: 56)
    }
}

extension TopBar where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil, close: Bool = false, onBack: @escaping () -> Void) {
        self.init(title: title, subtitle: subtitle, close: close, onBack: onBack) { EmptyView() }
    }
}

/// 轻提示 (安卓 Toast)
struct ToastView: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Capsule().fill(Color.black.opacity(0.78)))
            .padding(.horizontal, 32)
    }
}

/// 页面上的一条 toast 队列
@MainActor
final class Toaster: ObservableObject {
    @Published var text: String?
    private var work: DispatchWorkItem?

    func show(_ s: String) {
        text = s
        work?.cancel()
        let w = DispatchWorkItem { [weak self] in withAnimation { self?.text = nil } }
        work = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.2, execute: w)
    }
}

extension View {
    func toast(_ toaster: Toaster) -> some View {
        overlay(alignment: .bottom) {
            if let t = toaster.text {
                ToastView(text: t).padding(.bottom, 110).transition(.opacity).allowsHitTesting(false)
            }
        }
    }
}

/// 底栏工具按钮: 图标 + 字 (Widget.Xiangqi.Button.Tool)
struct ToolButton: View {
    let icon: String
    let title: String
    var tint: Color = Theme.textPrimary
    var enabled = true
    let action: () -> Void

    init(_ icon: String, _ title: String, tint: Color = Theme.textPrimary, enabled: Bool = true,
         action: @escaping () -> Void) {
        self.icon = icon
        self.title = title
        self.tint = tint
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(icon).renderingMode(.template).resizable().frame(width: 26, height: 26)
                Text(title).font(.system(size: 13, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
            }
            .foregroundStyle(enabled ? tint : Theme.textTertiary.opacity(0.6))
            .frame(maxWidth: .infinity, minHeight: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
