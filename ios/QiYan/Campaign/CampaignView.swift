import SwiftUI

/// 对弈闯关: 八关铺在一条自下而上蜿蜒的山路上, 山顶一只通关宝箱.
/// 已通关=象牙面朱砂「胜」印+星, 当前关=朱红战旗, 未解锁=灰石碑. 打开时滚到当前关.
struct CampaignView: View {
    @EnvironmentObject private var router: Router
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = ProgressStore.shared

    var body: some View {
        ZStack {
            CampaignBackground()
            VStack(spacing: 0) {
                TopBar(title: L("campaign_title"), onBack: { dismiss() })
                // 山路自下而上: 打开时当前关要在屏上, 不然老玩家落在第 1 关还以为进度丢了.
                // 内容里第 k 关所在的高度比例, 对齐到视口的同一比例处
                ScrollView(showsIndicators: false) {
                    CampaignPath(unlocked: store.campaignUnlocked, passed: store.campaignPassedLevels(),
                                 stars: { store.campaignStars($0) }) { level in
                        router.go(.game(GameConfig(difficulty: level, playerSide: Side.red, campaignLevel: level)))
                    }
                }
                .defaultScrollAnchor(UnitPoint(x: 0.5, y: CampaignPath.anchor(of: store.campaignUnlocked)))
            }
        }
    }
}

/// 闯关底: 远峰 - 雾带 - 孤舟 - 中山 - 近山, 固定在屏上, 滚动的只是路
struct CampaignBackground: View {
    var body: some View {
        GeometryReader { g in
            let top = g.safeAreaInsets.top
            let w = g.size.width + g.safeAreaInsets.leading + g.safeAreaInsets.trailing
            let h = g.size.height + top + g.safeAreaInsets.bottom
            ZStack(alignment: .topLeading) {
                PageBackground()
                Image("ink_peaks_far").resizable().frame(width: w, height: 150).offset(y: top + 40)
                mist.frame(width: w, height: 90).offset(y: top + 145)
                Image("ink_boat").resizable().frame(width: 64, height: 36).offset(x: w - 30 - 64, y: top + 160)
                Image("ink_peaks_mid").resizable().frame(width: w, height: 160).offset(y: h - 96 - 160)
                mist.frame(width: w, height: 90).offset(y: h - 211 - 90)
                Image("ink_mountains").resizable().frame(width: w, height: 120).offset(y: h - 120)
            }
            .frame(width: w, height: h, alignment: .topLeading)
            .ignoresSafeArea()
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    private var mist: some View {
        LinearGradient(colors: [.white.opacity(0), Theme.c("ink_mist"), .white.opacity(0)], startPoint: .top, endPoint: .bottom)
    }
}

struct CampaignPath: View {
    let unlocked: Int
    let passed: Set<Int>
    let stars: (Int) -> Int
    let onLevel: (Int) -> Void

    private let n = DifficultySettings.maxLevel
    private let rowSpacing = CampaignPath.rowSpacingPt, bottomPad = CampaignPath.bottomPadPt
    private let edgePad: CGFloat = 64

    private var current: Int { (1...n).first { $0 <= unlocked && !passed.contains($0) } ?? -1 }

    static let rowSpacingPt: CGFloat = 104, bottomPadPt: CGFloat = 72
    static var contentHeight: CGFloat { 96 + 8 + CGFloat(DifficultySettings.maxLevel - 1) * rowSpacingPt + bottomPadPt }

    /// 第 level 关 (连同下方的关名) 在内容里的纵向比例
    static func anchor(of level: Int) -> CGFloat {
        let y = contentHeight - bottomPadPt - CGFloat(max(level, 1) - 1) * rowSpacingPt + 30
        return min(max(y / contentHeight, 0), 1)
    }

    var body: some View {
        let height = CampaignPath.contentHeight
        GeometryReader { g in
            let pts = points(width: g.size.width, height: height)
            let chest = chestRect(pts)
            ZStack(alignment: .topLeading) {
                Canvas { ctx, _ in
                    var path = Path()
                    path.move(to: pts[0])
                    for i in 1..<n {
                        let midY = (pts[i - 1].y + pts[i].y) / 2
                        path.addCurve(to: pts[i], control1: CGPoint(x: pts[i - 1].x, y: midY), control2: CGPoint(x: pts[i].x, y: midY))
                    }
                    path.addCurve(to: CGPoint(x: chest.midX, y: chest.maxY), control1: CGPoint(x: pts[n - 1].x, y: chest.midY),
                                  control2: CGPoint(x: chest.midX, y: chest.midY))
                    ctx.stroke(path, with: .color(Theme.c("campaign_path")), style: StrokeStyle(lineWidth: 3.5, lineCap: .round, dash: [9, 7]))
                    drawChest(ctx, chest)
                }
                ForEach(1...n, id: \.self) { level in
                    node(level).id("level_\(level)").position(pts[level - 1])
                }
                Text(L("campaign_goal")).font(Font(PieceFont.font(14))).bold().foregroundStyle(Theme.warning)
                    .position(x: chest.midX, y: chest.maxY + 16)
            }
        }
        .frame(height: height)
    }

    private func points(width w: CGFloat, height h: CGFloat) -> [CGPoint] {
        let swing = w / 2 - edgePad
        return (0..<n).map { i in
            CGPoint(x: w / 2 + swing * CGFloat(sin(Double(i) * 0.85 + 2.4)), y: h - bottomPad - CGFloat(i) * rowSpacing)
        }
    }

    private func chestRect(_ pts: [CGPoint]) -> CGRect {
        let top = pts[n - 1]
        return CGRect(x: top.x - 23, y: top.y - 40 - 32, width: 46, height: 32)
    }

    private func drawChest(_ ctx: GraphicsContext, _ r: CGRect) {
        let lidH = r.height * 0.42
        let lid = CGRect(x: r.minX, y: r.minY, width: r.width, height: lidH)
        let body = CGRect(x: r.minX, y: r.minY + lidH, width: r.width, height: r.height - lidH)
        let lidPath = Path(roundedRect: lid, cornerRadius: 7), bodyPath = Path(roundedRect: body, cornerRadius: 7)
        ctx.fill(lidPath, with: .linearGradient(Gradient(colors: [Theme.c("gold_grad_1"), Theme.c("gold_grad_2")]),
                                                startPoint: CGPoint(x: 0, y: lid.minY), endPoint: CGPoint(x: 0, y: lid.maxY)))
        ctx.fill(bodyPath, with: .linearGradient(Gradient(colors: [Theme.c("gold_grad_2"), Theme.c("gold_grad_3")]),
                                                 startPoint: CGPoint(x: 0, y: body.minY), endPoint: CGPoint(x: 0, y: body.maxY)))
        let band = Theme.c("gold_pressed")
        ctx.fill(Path(CGRect(x: r.midX - 3.5, y: r.minY, width: 7, height: r.height)), with: .color(band))
        ctx.fill(Path(ellipseIn: CGRect(x: r.midX - 4.5, y: r.minY + lidH - 4.5, width: 9, height: 9)), with: .color(band))
        ctx.stroke(lidPath, with: .color(Theme.c("gold_stroke")), lineWidth: 1)
        ctx.stroke(bodyPath, with: .color(Theme.c("gold_stroke")), lineWidth: 1)
    }

    @ViewBuilder private func node(_ level: Int) -> some View {
        let name = L(CampaignStages.of(level).nameKey)
        let isCurrent = level == current
        let won = passed.contains(level)
        Button { if level <= unlocked { onLevel(level) } } label: {
            ZStack {
                if isCurrent {
                    Flag().offset(y: -11)
                    label(name, color: Theme.action, bold: true).offset(y: 30)
                } else if won {
                    seal
                    starRow(stars(level)).offset(y: 24)
                    label(name, color: Theme.textSecondary, bold: false).offset(y: 40)
                } else {
                    stone(level)
                    label(name, color: Theme.textTertiary.opacity(0.8), bold: false).offset(y: 30)
                }
            }
            .frame(width: 96, height: 96)
            .contentShape(Circle().inset(by: 14))
        }
        .buttonStyle(.plain)
        .disabled(level > unlocked)
        .accessibilityLabel("\(L("campaign_level_format", level)) \(name)")
        .accessibilityValue(won ? L("puzzle_solved") : (level > unlocked ? L("rank_hall_locked") : ""))
    }

    private func label(_ text: String, color: Color, bold: Bool) -> some View {
        Text(text).font(Font(PieceFont.font(12))).fontWeight(bold ? .bold : .regular).foregroundStyle(color)
            .fixedSize()
    }

    private var seal: some View {
        ZStack {
            Circle().fill(Theme.c("piece_shadow")).frame(width: 34, height: 34).offset(x: 1.5, y: 2)
            Circle().fill(Theme.c("piece_face")).frame(width: 34, height: 34)
            Circle().strokeBorder(Theme.c("piece_red"), lineWidth: 2.2).frame(width: 34, height: 34)
            Text("胜").font(Font(PieceFont.font(17))).bold().foregroundStyle(Theme.c("piece_red"))
        }
    }

    private func stone(_ level: Int) -> some View {
        ZStack {
            Circle().fill(Theme.surfaceRaised)
            Circle().strokeBorder(Theme.borderStrong.opacity(0.73), lineWidth: 1.5)
            Text("\(level)").font(Font(PieceFont.font(15))).foregroundStyle(Theme.textTertiary.opacity(0.8))
        }
        .frame(width: 30, height: 30)
    }

    private func starRow(_ count: Int) -> some View {
        let shown = min(max(count, 0), 3)
        return HStack(spacing: 3.2) {
            ForEach(0..<3, id: \.self) { i in
                if i < shown { Circle().fill(Theme.c("rank_gold")).frame(width: 4.8, height: 4.8) }
                else { Circle().stroke(Theme.c("rank_gold"), lineWidth: 1).frame(width: 4.8, height: 4.8) }
            }
        }
        .opacity(shown == 0 ? 0 : 1)
    }
}

/// 当前关的朱红战旗
private struct Flag: View {
    var body: some View {
        Canvas { ctx, size in
            let x = size.width / 2 - 18, base = size.height / 2 + 19
            let poleTop = base - 8 - 30
            var pole = Path()
            pole.move(to: CGPoint(x: x, y: base))
            pole.addLine(to: CGPoint(x: x, y: poleTop))
            ctx.stroke(pole, with: .color(Theme.c("board_palace")), style: StrokeStyle(lineWidth: 3, lineCap: .round))
            let bw: CGFloat = 36, bh: CGFloat = 24
            var flag = Path()
            flag.move(to: CGPoint(x: x, y: poleTop))
            flag.addLine(to: CGPoint(x: x + bw, y: poleTop + 2.5))
            flag.addQuadCurve(to: CGPoint(x: x + bw, y: poleTop + bh - 2.5), control: CGPoint(x: x + bw + 4, y: poleTop + bh / 2))
            flag.addLine(to: CGPoint(x: x, y: poleTop + bh))
            flag.closeSubpath()
            ctx.fill(flag, with: .color(Theme.action))
            ctx.stroke(flag, with: .color(Theme.c("piece_red_edge")), lineWidth: 1)
            ctx.draw(Text("战").font(Font(PieceFont.font(14))).bold().foregroundColor(Theme.c("piece_text")),
                     at: CGPoint(x: x + bw / 2 + 1.5, y: poleTop + bh / 2))
        }
        .frame(width: 96, height: 76)
    }
}
