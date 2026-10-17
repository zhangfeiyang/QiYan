import SwiftUI

/// 局势胜率走势图: 50% 虚线基准, 红优向上/黑优向下渐变, 妙着/失误/漏着标记, 当前手光标; 拖动寻步
struct AdvantageGraph: View {
    let winRates: [Double]
    let evaluations: [MoveEvaluation]
    let step: Int
    let onStep: (Int) -> Void

    private let padX: CGFloat = 8, padY: CGFloat = 6

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width - padX * 2, h = geo.size.height - padY * 2
            let total = max(winRates.count - 1, 1)
            let pt = { (i: Int) -> CGPoint in
                CGPoint(x: padX + CGFloat(i) / CGFloat(total) * w,
                        y: padY + (1 - CGFloat(min(max(winRates[i], 0), 100)) / 100) * h)
            }
            let mid = padY + h / 2
            ZStack(alignment: .topLeading) {
                Canvas { ctx, _ in
                    var base = Path()
                    base.move(to: CGPoint(x: padX, y: mid))
                    base.addLine(to: CGPoint(x: padX + w, y: mid))
                    ctx.stroke(base, with: .color(Color(white: 0.53, opacity: 0.33)), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    guard winRates.count > 1 else { return }
                    var curve = Path()
                    for i in winRates.indices { i == 0 ? curve.move(to: pt(i)) : curve.addLine(to: pt(i)) }
                    var fill = curve
                    fill.addLine(to: CGPoint(x: padX + w, y: mid))
                    fill.addLine(to: CGPoint(x: padX, y: mid))
                    fill.closeSubpath()
                    ctx.drawLayer { c in
                        c.clip(to: Path(CGRect(x: padX, y: padY, width: w, height: h / 2)))
                        c.fill(fill, with: .linearGradient(Gradient(colors: [Color(hex: 0x45D32F2F), Color(hex: 0x05D32F2F)]),
                                                          startPoint: CGPoint(x: 0, y: padY), endPoint: CGPoint(x: 0, y: mid)))
                    }
                    ctx.drawLayer { c in
                        c.clip(to: Path(CGRect(x: padX, y: mid, width: w, height: h / 2)))
                        c.fill(fill, with: .linearGradient(Gradient(colors: [Color(hex: 0x051976D2), Color(hex: 0x401976D2)]),
                                                          startPoint: CGPoint(x: 0, y: mid), endPoint: CGPoint(x: 0, y: padY + h)))
                    }
                    ctx.stroke(curve, with: .color(Color(hex: 0xFFC62828)),
                               style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                    for ev in evaluations where ev.stepIndex >= 1 && ev.stepIndex < winRates.count {
                        let p = pt(ev.stepIndex)
                        let (r, color, ring): (CGFloat, UInt32, Bool) = switch ev.quality {
                        case .brilliant: (4.5, 0xFF00B0FF, true)
                        case .blunder: (4.2, 0xFFE53935, true)
                        case .mistake: (3.2, 0xFFFF9800, false)
                        default: (0, 0, false)
                        }
                        guard r > 0 else { continue }
                        let dot = Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r))
                        ctx.fill(dot, with: .color(Color(hex: color)))
                        if ring { ctx.stroke(dot, with: .color(.white), lineWidth: 1.2) }
                    }
                    let gold = Color(hex: 0xFFD4AF37)
                    let c = pt(min(max(step, 0), winRates.count - 1))
                    var cursor = Path()
                    cursor.move(to: CGPoint(x: c.x, y: padY))
                    cursor.addLine(to: CGPoint(x: c.x, y: padY + h))
                    ctx.stroke(cursor, with: .color(gold), lineWidth: 1.8)
                    ctx.fill(Path(ellipseIn: CGRect(x: c.x - 5.5, y: c.y - 5.5, width: 11, height: 11)), with: .color(gold))
                    ctx.fill(Path(ellipseIn: CGRect(x: c.x - 2.5, y: c.y - 2.5, width: 5, height: 5)), with: .color(.white))
                }
                Text(L("replay_red_better")).font(.system(size: 9.5, weight: .bold)).foregroundStyle(Color(white: 0.53, opacity: 0.53))
                    .offset(x: padX, y: padY - 2)
                Text(L("replay_black_better")).font(.system(size: 9.5, weight: .bold)).foregroundStyle(Color(white: 0.53, opacity: 0.53))
                    .offset(x: padX, y: padY + h - 12)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                guard winRates.count > 1 else { return }
                let f = min(max((g.location.x - padX) / w, 0), 1)
                let target = Int((f * CGFloat(total)).rounded())
                if target != step { onStep(target) }
            })
        }
        .accessibilityElement()
        .accessibilityLabel(L("replay_red_winrate", Int((winRates.indices.contains(step) ? winRates[step] : 50).rounded())))
        .accessibilityAdjustableAction { dir in
            switch dir {
            case .increment: onStep(min(step + 1, winRates.count - 1))
            case .decrement: onStep(max(step - 1, 0))
            @unknown default: break
            }
        }
    }
}

extension Color {
    /// 0xAARRGGBB
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: Double((hex >> 24) & 0xFF) / 255)
    }
}
