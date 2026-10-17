import SwiftUI
import UIKit

/// The xiangqi board (port of the Android BoardView): wooden frame and grid, ivory pieces with
/// side-colored glyphs, move/selection/hint/threat/check highlights, the sliding move animation,
/// landing ripples and the capture "battle" effect. Drawn with Core Graphics, animated by a
/// display link that only runs while something moves.
final class BoardUIView: UIView {
    // MARK: Inputs
    var onMove: ((Move) -> Void)?
    var onSelect: ((Int, [Move]) -> Void)?
    var onSelectHaptic: (() -> Void)?
    /// 编辑模式: 点击只上报格子 (摆子/识别校对)
    var editMode = false
    var onCellTap: ((Int) -> Void)?
    var inputEnabled = true

    private(set) var position = Position()
    private var selected = -1
    private var legalFromSelected: [Move] = []
    private var lastMove: Move?
    private var hintMove: Move?
    private var hintGhostPiece = 0
    private var checkedKing = -1
    private var suspect: Set<Int> = []
    private var redThreatened: Set<Int> = []
    private var blackThreatened: Set<Int> = []
    private var rippleByPlayer = true

    var flipped = false { didSet { setNeedsDisplay() } }
    var showCoordinates = true { didSet { setNeedsDisplay() } }
    var captureFxEnabled = true
    var skin: BoardSkin = .wood { didSet { palette = BoardPalette.of(skin); setNeedsDisplay() } }
    var latinPieces = false { didSet { setNeedsDisplay() } }
    var riverText = "楚 河　　汉 界" { didSet { setNeedsDisplay() } }
    private lazy var palette = BoardPalette.of(skin)

    private var motionAllowed: Bool { AppSettings.shared.motionAllowed }

    // MARK: Geometry
    private var cell: CGFloat = 0
    private var left: CGFloat = 0, top: CGFloat = 0
    private var radius: CGFloat = 0
    private var boardRect = CGRect.zero
    private var playRect = CGRect.zero
    private let marginCells: CGFloat = 1.9
    private let woodMarginCells: CGFloat = 0.90
    private let frameCells: CGFloat = 0.12

    // MARK: Animation state
    private struct Anim {
        var start: CFTimeInterval
        var duration: CFTimeInterval
        var repeats = 0
        func progress(_ now: CFTimeInterval) -> CGFloat? {
            let t = (now - start) / duration
            if t >= Double(repeats + 1) { return nil }
            return CGFloat(t - floor(t))
        }
    }
    private var link: CADisplayLink?
    private var now: CFTimeInterval = 0
    private var moveAnim: (move: Move, piece: Int, anim: Anim)?
    private var landing: (square: Int, anim: Anim)?
    private var checkPulse: (square: Int, anim: Anim)?
    private var landingShake: Anim?
    private var selectionStart: CFTimeInterval = 0
    private var boardTag: (square: Int, text: String, color: UIColor, anim: Anim, isStatic: Bool)?

    private struct KillFx { let duration: Double, shake: CGFloat, slashes: Int, embers: Int, splash: CGFloat }
    private let killByType: [KillFx] = [
        KillFx(duration: 1.0, shake: 0.025, slashes: 3, embers: 16, splash: 0.9),
        KillFx(duration: 0.65, shake: 0, slashes: 1, embers: 8, splash: 0.5),
        KillFx(duration: 0.65, shake: 0, slashes: 1, embers: 8, splash: 0.5),
        KillFx(duration: 0.75, shake: 0, slashes: 1, embers: 10, splash: 0.65),
        KillFx(duration: 0.85, shake: 0.015, slashes: 2, embers: 12, splash: 0.8),
        KillFx(duration: 0.75, shake: 0, slashes: 1, embers: 10, splash: 0.65),
        KillFx(duration: 0.6, shake: 0, slashes: 1, embers: 6, splash: 0.4),
    ]
    private struct Shard { let vx, vy, spin, size: CGFloat; let color: UIColor }
    private var fx: (square: Int, piece: Int, attacker: Int, dx: CGFloat, dy: CGFloat, cfg: KillFx, anim: Anim, shards: [Shard])?
    private let fxImpact: CGFloat = 0.26
    private let fxSplit: CGFloat = 0.36
    private static let slashImage = UIImage(named: "fx_slash")?.cgImage
    private static let splashImage = UIImage(named: "fx_splash")?.cgImage
    private static let goldImage = UIImage(named: "fx_gold")?.cgImage
    private static let burstSheet = UIImage(named: "fx_burst_sheet")?.cgImage
    private static let goldSheet = UIImage(named: "fx_gold_sheet")?.cgImage
    private var frameCache: [String: CGImage] = [:]

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        contentMode = .redraw
        isAccessibilityElement = true
        accessibilityTraits = .allowsDirectInteraction
    }

    required init?(coder: NSCoder) { fatalError() }

    deinit { link?.invalidate() }

    // MARK: Public API (same verbs as the Android view)

    /// position 是走子之后的局面; animateMove 非空时画成从起点滑到终点
    func setPosition(_ p: Position, animateMove: Move? = nil) {
        if animateMove == nil && p.hash == position.hash && p.squares == position.squares { return }
        landing = nil
        checkPulse = nil
        if animateMove == nil { fx = nil; boardTag = nil }
        position = p.copy()
        lastMove = nil
        hintMove = nil
        selected = -1
        legalFromSelected = []
        checkedKing = -1
        if let m = animateMove, motionAllowed, cell > 0, position.pieceAt(m.to) != 0 {
            moveAnim = (m, position.pieceAt(m.to), Anim(start: CACurrentMediaTime(), duration: 0.22))
            startLink()
        } else {
            moveAnim = nil
        }
        setNeedsDisplay()
    }

    func setLastMove(_ m: Move?) { lastMove = m; setNeedsDisplay() }

    func setHint(_ m: Move?) {
        hintMove = m
        hintGhostPiece = m.map { position.pieceAt($0.from) } ?? 0
        setNeedsDisplay()
    }

    func setCheckedKing(_ sq: Int) {
        if sq != checkedKing && sq >= 0 && motionAllowed {
            checkPulse = (sq, Anim(start: CACurrentMediaTime(), duration: 0.72, repeats: 2))
            startLink()
        }
        if sq < 0 { checkPulse = nil }
        checkedKing = sq
        setNeedsDisplay()
    }

    func setThreats(red: [Int], black: [Int]) {
        redThreatened = Set(red)
        blackThreatened = Set(black)
        setNeedsDisplay()
    }

    func setSuspect(_ squares: Set<Int>) { suspect = squares; setNeedsDisplay() }
    func setRippleByPlayer(_ byPlayer: Bool) { rippleByPlayer = byPlayer }

    func setSelectedSquare(_ sq: Int) {
        selected = sq
        legalFromSelected = []
        selectionStart = CACurrentMediaTime()
        syncLink()
        setNeedsDisplay()
    }

    func clearSelection() {
        selected = -1
        legalFromSelected = []
        setNeedsDisplay()
    }

    /// 在某格上方弹一条会升起、淡出的文字标签 (吃车/将军/绝杀)
    func showTag(_ square: Int, _ text: String, color: UIColor) {
        guard square >= 0, square < Square.count, !text.isEmpty else { return }
        boardTag = (square, text, color, Anim(start: CACurrentMediaTime(), duration: 0.95), !motionAllowed)
        startLink()
        setNeedsDisplay()
    }

    /// 吃子的战场厮杀特效; label 非空时同时弹文字标签 (特效关掉也照弹)
    func playCapture(square: Int, captured: Int, attacker: Int, attackerSquare: Int, label: String?) {
        if let label { showTag(square, label, color: .token("action")) }
        guard captureFxEnabled, motionAllowed, square >= 0, square < Square.count else { return }
        let piece = Piece.fromCode(captured)
        let cfg = killByType[min(max(piece.type - 1, 0), killByType.count - 1)]
        var dx: CGFloat = 0, dy: CGFloat = -1
        if attackerSquare >= 0, attackerSquare < Square.count, attackerSquare != square {
            let to = center(square), from = center(attackerSquare)
            let len = hypot(to.x - from.x, to.y - from.y)
            if len > 1 { dx = (to.x - from.x) / len; dy = (to.y - from.y) / len }
        }
        var rng = SeededGenerator(seed: UInt64(captured * 31 + square))
        let warm = piece.isRed ? palette.pieceRed : palette.pieceBlack
        let spark = UIColor.token("fx_spark")
        let shards = (0..<cfg.embers).map { i -> Shard in
            let ang = CGFloat(i) / CGFloat(cfg.embers) * .pi * 2 + CGFloat.random(in: 0..<0.4, using: &rng)
            let speed = 0.5 + CGFloat.random(in: 0..<0.9, using: &rng)
            return Shard(vx: cos(ang) * speed, vy: sin(ang) * speed, spin: (CGFloat.random(in: 0..<1, using: &rng) - 0.5) * 720,
                         size: 0.06 + CGFloat.random(in: 0..<0.1, using: &rng), color: i % 3 == 0 ? spark : warm)
        }
        fx = (square, captured, attacker, dx, dy, cfg, Anim(start: CACurrentMediaTime(), duration: cfg.duration), shards)
        startLink()
    }

    // MARK: Display link

    private func startLink() {
        guard link == nil, window != nil else { return }
        let l = CADisplayLink(target: self, selector: #selector(tick))
        l.add(to: .main, forMode: .common)
        link = l
    }

    private func syncLink() {
        if selected >= 0 && motionAllowed { startLink() }
    }

    @objc private func tick() {
        now = CACurrentMediaTime()
        if let m = moveAnim, m.anim.progress(now) == nil {
            moveAnim = nil
            landing = (m.move.to, Anim(start: now, duration: 0.38))
            landingShake = Anim(start: now, duration: 0.13)
        }
        if let l = landing, l.anim.progress(now) == nil { landing = nil }
        if let s = landingShake, s.progress(now) == nil { landingShake = nil }
        if let c = checkPulse, c.anim.progress(now) == nil { checkPulse = nil }
        if let t = boardTag, !t.isStatic, t.anim.progress(now) == nil { boardTag = nil }
        if let t = boardTag, t.isStatic, now - t.anim.start > t.anim.duration { boardTag = nil }
        if let f = fx, f.anim.progress(now) == nil { fx = nil }
        let busy = moveAnim != nil || landing != nil || landingShake != nil || checkPulse != nil || boardTag != nil || fx != nil
            || (selected >= 0 && motionAllowed)
        if !busy {
            link?.invalidate()
            link = nil
        }
        setNeedsDisplay()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            link?.invalidate()
            link = nil
        }
    }

    // MARK: Layout

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width, h = bounds.height
        guard w > 0, h > 0 else { return }
        cell = min(w / (8 + marginCells), h / (9 + marginCells))
        let bw = cell * 8, bh = cell * 9
        left = (w - bw) / 2
        top = (h - bh) / 2
        radius = cell * 0.42
        let wood = cell * woodMarginCells
        boardRect = CGRect(x: left - wood, y: top - wood, width: bw + 2 * wood, height: bh + 2 * wood)
        playRect = boardRect.insetBy(dx: cell * frameCells, dy: cell * frameCells)
        #if DEBUG
        // UI 测试按交点坐标点棋盘
        accessibilityIdentifier = "board"
        accessibilityValue = "\(left),\(top),\(cell)"
        #endif
        setNeedsDisplay()
    }

    /// 棋盘格 -> 交点中心
    func center(_ sq: Int) -> CGPoint {
        var r = Square.row(sq), c = Square.col(sq)
        if flipped { r = Square.rows - 1 - r; c = Square.cols - 1 - c }
        return CGPoint(x: left + CGFloat(c) * cell, y: top + CGFloat(r) * cell)
    }

    private func square(at p: CGPoint) -> Int {
        guard cell > 0 else { return -1 }
        let fc = (p.x - left) / cell, fr = (p.y - top) / cell
        let col = Int(fc.rounded()), row = Int(fr.rounded())
        guard Square.valid(row, col), abs(fc - CGFloat(col)) <= 0.5, abs(fr - CGFloat(row)) <= 0.5 else { return -1 }
        return flipped ? Square.make(Square.rows - 1 - row, Square.cols - 1 - col) : Square.make(row, col)
    }

    // MARK: Touch

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard inputEnabled, let t = touches.first else { return }
        handleTap(square(at: t.location(in: self)))
    }

    private func handleTap(_ sq: Int) {
        if editMode {
            if sq >= 0 { onCellTap?(sq) }
            return
        }
        guard sq >= 0 else { clearSelection(); return }
        let piece = position.pieceAt(sq)
        let own = piece != 0 && Piece.fromCode(piece).color == position.sideToMove
        if selected >= 0 && sq == selected { clearSelection(); return }
        if own {
            selected = sq
            selectionStart = CACurrentMediaTime()
            legalFromSelected = position.legalMoves().filter { $0.from == sq }
            onSelectHaptic?()
            onSelect?(sq, legalFromSelected)
            syncLink()
            setNeedsDisplay()
            return
        }
        if selected >= 0, let move = legalFromSelected.first(where: { $0.to == sq }) {
            clearSelection()
            onMove?(move)
            return
        }
        clearSelection()
    }

    // MARK: Drawing

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), cell > 0 else { return }
        now = CACurrentMediaTime()
        ctx.saveGState()
        var shake = CGPoint.zero
        if let f = fx, f.cfg.shake > 0, let t = f.anim.progress(now) {
            let k: CGFloat = t < fxImpact ? t / fxImpact : (t < 0.55 ? 1 - (t - fxImpact) / (0.55 - fxImpact) : 0)
            if k > 0 {
                let a = f.cfg.shake * cell * k
                shake = CGPoint(x: sin(t * 71) * a, y: cos(t * 53) * a * 0.6)
            }
        }
        if let s = landingShake, let t = s.progress(now) {
            // 0 -> 2.5 -> -1 -> 0 像素的木质回弹
            let v: CGFloat = t < 0.33 ? 2.5 * t / 0.33 : (t < 0.66 ? 2.5 - 3.5 * (t - 0.33) / 0.33 : -1 + (t - 0.66) / 0.34)
            shake.y += v
        }
        ctx.translateBy(x: shake.x, y: shake.y)
        drawBoard(ctx)
        drawGrid(ctx)
        drawMarks(ctx)
        drawPalace(ctx)
        drawRiver(ctx)
        if showCoordinates { drawCoordinates(ctx) }
        drawThreats(ctx)
        drawHighlights(ctx)
        if let l = landing, let t = l.anim.progress(now) {
            drawPulse(ctx, l.square, t, rippleByPlayer ? .token("fx_spark") : .token("board_ripple_opponent"))
        }
        if let c = checkPulse, let t = c.anim.progress(now) { drawPulse(ctx, c.square, t, .token("hl_check")) }
        drawPieces(ctx)
        drawSelection(ctx)
        if let m = moveAnim, let t = m.anim.progress(now) {
            let e = easing(t)
            let a = center(m.move.from), b = center(m.move.to)
            drawPiece(ctx, CGPoint(x: a.x + (b.x - a.x) * e, y: a.y + (b.y - a.y) * e), m.piece)
        }
        drawHint(ctx)
        drawCaptureFx(ctx)
        drawTag(ctx)
        ctx.restoreGState()
    }

    /// cubic-bezier(0.3, 0, 0.2, 1)
    private func easing(_ x: CGFloat) -> CGFloat {
        var t = x
        for _ in 0..<6 {
            let bx = 3 * (1 - t) * (1 - t) * t * 0.3 + 3 * (1 - t) * t * t * 0.2 + t * t * t
            let dx = 3 * (1 - t) * (1 - t) * 0.3 + 6 * (1 - t) * t * (0.2 - 0.3) + 3 * t * t * (1 - 0.2)
            if abs(dx) < 1e-6 { break }
            t -= (bx - x) / dx
            t = min(max(t, 0), 1)
        }
        return 3 * (1 - t) * t * t + t * t * t
    }

    private func line(_ ctx: CGContext, _ a: CGPoint, _ b: CGPoint) {
        ctx.move(to: a)
        ctx.addLine(to: b)
        ctx.strokePath()
    }

    private func drawBoard(_ ctx: CGContext) {
        let frame = UIBezierPath(roundedRect: boardRect, cornerRadius: 7)
        ctx.saveGState()
        ctx.addPath(frame.cgPath)
        ctx.clip()
        let g = CGGradient(colorsSpace: nil, colors: [palette.frameTop.cgColor, palette.frameEdge.cgColor] as CFArray, locations: nil)!
        ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: boardRect.minY), end: CGPoint(x: 0, y: boardRect.maxY), options: [])
        ctx.restoreGState()
        ctx.setStrokeColor(palette.frameEdge.cgColor)
        ctx.setLineWidth(1)
        ctx.addPath(frame.cgPath)
        ctx.strokePath()

        let play = UIBezierPath(roundedRect: playRect, cornerRadius: 4)
        ctx.saveGState()
        ctx.addPath(play.cgPath)
        ctx.clip()
        let bg = CGGradient(colorsSpace: nil, colors: palette.boardGrad.map(\.cgColor) as CFArray, locations: [0, 0.4, 0.7, 1])!
        ctx.drawLinearGradient(bg, start: boardRect.origin, end: CGPoint(x: boardRect.maxX, y: boardRect.maxY), options: [])
        ctx.restoreGState()
        ctx.setStrokeColor(palette.border.cgColor)
        ctx.setLineWidth(max(cell * 0.045, 1.5))
        ctx.addPath(play.cgPath)
        ctx.strokePath()

        // 木纹细线
        ctx.setStrokeColor(palette.grain.cgColor)
        ctx.setLineWidth(0.6)
        for i in 0...30 {
            let y = boardRect.minY + boardRect.height / 30 * CGFloat(i)
            let wave = sin(CGFloat(i) * 0.7) * 2
            line(ctx, CGPoint(x: boardRect.minX + wave, y: y), CGPoint(x: boardRect.maxX - wave, y: y))
        }
    }

    private func drawGrid(_ ctx: CGContext) {
        ctx.setStrokeColor(palette.line.cgColor)
        ctx.setLineWidth(max(cell * 0.022, 1))
        let right = left + cell * 8, bottom = top + cell * 9
        for i in 0..<Square.rows {
            let y = top + CGFloat(i) * cell
            line(ctx, CGPoint(x: left, y: y), CGPoint(x: right, y: y))
        }
        for i in 0..<Square.cols {
            let x = left + CGFloat(i) * cell
            if i == 0 || i == Square.cols - 1 {
                line(ctx, CGPoint(x: x, y: top), CGPoint(x: x, y: bottom))
            } else {
                line(ctx, CGPoint(x: x, y: top), CGPoint(x: x, y: top + 4 * cell))
                line(ctx, CGPoint(x: x, y: top + 5 * cell), CGPoint(x: x, y: bottom))
            }
        }
    }

    private func drawPalace(_ ctx: CGContext) {
        ctx.setStrokeColor(palette.palace.cgColor)
        for y0 in [top, top + 7 * cell] {
            line(ctx, CGPoint(x: left + 3 * cell, y: y0), CGPoint(x: left + 5 * cell, y: y0 + 2 * cell))
            line(ctx, CGPoint(x: left + 5 * cell, y: y0), CGPoint(x: left + 3 * cell, y: y0 + 2 * cell))
        }
    }

    /// 炮位/兵位的直角标记
    private func drawMarks(_ ctx: CGContext) {
        ctx.setStrokeColor(palette.line.cgColor)
        ctx.setLineWidth(max(cell * 0.028, 1))
        ctx.setLineCap(.round)
        var spots: [(Int, Int)] = []
        for r in [2, 7] { for c in [1, 7] { spots.append((r, c)) } }
        for r in [3, 6] { for c in stride(from: 0, to: 9, by: 2) { spots.append((r, c)) } }
        let gap = cell * 0.07, arm = cell * 0.17
        for (r, c) in spots {
            let cx = left + CGFloat(c) * cell, cy = top + CGFloat(r) * cell
            for sx in [-1, 1] as [CGFloat] {
                if (c == 0 && sx < 0) || (c == 8 && sx > 0) { continue }
                for sy in [-1, 1] as [CGFloat] {
                    let o = CGPoint(x: cx + sx * gap, y: cy + sy * gap)
                    line(ctx, o, CGPoint(x: o.x + sx * arm, y: o.y))
                    line(ctx, o, CGPoint(x: o.x, y: o.y + sy * arm))
                }
            }
        }
        ctx.setLineCap(.butt)
    }

    private func text(_ s: String, font: UIFont, color: UIColor, center p: CGPoint, align: NSTextAlignment = .center,
                      bold: Bool = false) {
        var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        if bold { attrs[.strokeWidth] = -3.0; attrs[.strokeColor] = color }
        let str = NSAttributedString(string: s, attributes: attrs)
        let size = str.size()
        let y = p.y - (font.ascender - font.descender) / 2
        let x: CGFloat
        switch align {
        case .right: x = p.x - size.width
        case .left: x = p.x
        default: x = p.x - size.width / 2
        }
        str.draw(at: CGPoint(x: x, y: y))
    }

    private func drawRiver(_ ctx: CGContext) {
        let isLatin = !riverText.unicodeScalars.contains { (0x4E00...0x9FFF).contains($0.value) }
        let font = PieceFont.font(radius * (isLatin ? 0.72 : 1.4))
        text(riverText, font: font, color: palette.river, center: CGPoint(x: left + 4 * cell, y: top + 4.5 * cell))
    }

    private func drawCoordinates(_ ctx: CGContext) {
        let font = UIFont(name: "Georgia", size: radius * 0.62) ?? .systemFont(ofSize: radius * 0.62)
        let band = (radius + cell * (woodMarginCells - frameCells)) / 2
        for c in 0..<Square.cols {
            let x = left + CGFloat(c) * cell
            text(BoardUIView.fileLabel(c, flipped: flipped, top: true), font: font, color: palette.coord, center: CGPoint(x: x, y: top - band))
            text(BoardUIView.fileLabel(c, flipped: flipped, top: false), font: font, color: palette.coord,
                 center: CGPoint(x: x, y: top + 9 * cell + band))
        }
        let gap = radius * 0.12
        for r in 0..<Square.rows {
            let y = top + CGFloat(r) * cell
            let label = String(Square.rows - r)
            text(label, font: font, color: palette.coord, center: CGPoint(x: left - radius - gap, y: y), align: .right)
            text(label, font: font, color: palette.coord, center: CGPoint(x: left + 8 * cell + radius + gap, y: y), align: .left)
        }
    }

    /// 每一方从自己的右手边数起; 翻面后号数跟着棋盘的列走
    static func fileLabel(_ screenCol: Int, flipped: Bool, top: Bool) -> String {
        let phys = flipped ? Square.cols - 1 - screenCol : screenCol
        return top == flipped ? String(9 - phys) : String(1 + phys)
    }

    private func fillCircle(_ ctx: CGContext, _ c: CGPoint, _ r: CGFloat, _ color: UIColor) {
        ctx.setFillColor(color.cgColor)
        ctx.fillEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
    }

    private func strokeCircle(_ ctx: CGContext, _ c: CGPoint, _ r: CGFloat, _ color: UIColor, _ width: CGFloat) {
        ctx.setStrokeColor(color.cgColor)
        ctx.setLineWidth(width)
        ctx.strokeEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
    }

    private func drawThreats(_ ctx: CGContext) {
        for sq in redThreatened { fillCircle(ctx, center(sq), radius * 1.05, .token("hl_capture")) }
        for sq in blackThreatened { fillCircle(ctx, center(sq), radius * 1.05, .token("hl_move")) }
    }

    private func drawHighlights(_ ctx: CGContext) {
        if let m = lastMove {
            strokeCircle(ctx, center(m.from), radius * 0.95, .token("hl_last"), max(radius * 0.06, 1.5))
            strokeCircle(ctx, center(m.to), radius * 0.95, .token("hl_last"), max(radius * 0.06, 1.5))
        }
        if selected >= 0 { strokeCircle(ctx, center(selected), radius * 0.95, .token("hl_select"), max(radius * 0.09, 2)) }
        for m in legalFromSelected {
            let c = center(m.to)
            if position.pieceAt(m.to) != 0 {
                let r = radius * 0.95
                for frac in [0.4, 0.6] as [CGFloat] {
                    for sign in [-1, 1] as [CGFloat] {
                        fillCircle(ctx, CGPoint(x: c.x + r * frac * sign, y: c.y + r * frac * sign), r * 0.12, .token("hl_capture"))
                    }
                }
            } else {
                let r = radius * 0.18
                fillCircle(ctx, c, r * 1.8, .token("hl_move_halo"))
                fillCircle(ctx, c, r, .token("hl_move"))
            }
        }
        if checkedKing >= 0 { strokeCircle(ctx, center(checkedKing), radius * 1.1, .token("hl_check"), max(radius * 0.1, 2)) }
        for sq in suspect { strokeCircle(ctx, center(sq), radius * 0.95, .token("warning"), max(radius * 0.09, 2)) }
    }

    private func drawPulse(_ ctx: CGContext, _ sq: Int, _ t: CGFloat, _ color: UIColor) {
        let c = center(sq)
        strokeCircle(ctx, c, radius * (1.05 + t * 0.62), color.withAlphaComponent((1 - t) * 0.65), radius * (0.09 - t * 0.04))
        strokeCircle(ctx, c, radius * (1.02 + t * 0.32), color.withAlphaComponent((1 - t) * 0.3), radius * (0.09 - t * 0.04))
    }

    private func drawPieces(_ ctx: CGContext) {
        let skip = moveAnim?.move.to ?? -1
        for sq in 0..<Square.count where sq != skip {
            let code = position.pieceAt(sq)
            guard code != 0 else { continue }
            let c = center(sq)
            if sq == selected {
                drawPiece(ctx, CGPoint(x: c.x, y: c.y - radius * 0.16), code, scale: 1.07, lifted: true)
            } else {
                drawPiece(ctx, c, code)
            }
        }
    }

    /// 象牙面 + 本方色字与刻环; 分层: 投影 → 外沿 → 象牙面 → 背光暗部 → 高光 → 刻环 → 字
    func drawPiece(_ ctx: CGContext, _ c: CGPoint, _ code: Int, scale: CGFloat = 1, lifted: Bool = false, alpha: CGFloat = 1) {
        let piece = Piece.fromCode(code)
        guard piece != .none else { return }
        let r = radius
        ctx.saveGState()
        ctx.setAlpha(alpha)
        ctx.translateBy(x: c.x, y: c.y)
        ctx.scaleBy(x: scale, y: scale)
        let shadow = UIColor.token("piece_shadow")
        if lifted {
            fillCircle(ctx, CGPoint(x: 2.5, y: 5.5), r * 1.16, shadow.withAlphaComponent(0.4))
        } else {
            fillCircle(ctx, CGPoint(x: 1.5, y: 2), r * 1.06, shadow)
        }
        fillCircle(ctx, .zero, r, piece.isRed ? palette.pieceRedEdge : palette.pieceBlackEdge)
        fillCircle(ctx, .zero, r * 0.94, palette.pieceFace)
        let face = CGRect(x: -r * 0.94, y: -r * 0.94, width: r * 1.88, height: r * 1.88)
        ctx.saveGState()
        ctx.addEllipse(in: face)
        ctx.clip()
        let shade = palette.pieceShade
        let shadeG = CGGradient(colorsSpace: nil, colors: [shade.cgColor, shade.withAlphaComponent(0).cgColor] as CFArray, locations: nil)!
        ctx.drawRadialGradient(shadeG, startCenter: CGPoint(x: r * 0.38, y: r * 0.45), startRadius: 0,
                               endCenter: CGPoint(x: r * 0.38, y: r * 0.45), endRadius: r * 0.95, options: [])
        let hi = CGGradient(colorsSpace: nil, colors: palette.pieceGrad.map(\.cgColor) as CFArray, locations: [0, 0.5, 1])!
        ctx.drawRadialGradient(hi, startCenter: CGPoint(x: -r * 0.3, y: -r * 0.3), startRadius: 0,
                               endCenter: CGPoint(x: -r * 0.3, y: -r * 0.3), endRadius: r * 0.7, options: [])
        ctx.restoreGState()
        let ink = piece.isRed ? palette.pieceRed : palette.pieceBlack
        strokeCircle(ctx, .zero, r * 0.8, ink, r * 0.055)
        let label = latinPieces ? String(piece.fen).uppercased() : piece.chinese
        text(label, font: PieceFont.font(r * (latinPieces ? 1.55 : 1.3)), color: ink, center: .zero, bold: true)
        ctx.restoreGState()
    }

    private func drawSelection(_ ctx: CGContext) {
        guard selected >= 0 else { return }
        let c = center(selected)
        let p = CGPoint(x: c.x, y: c.y - radius * 0.16)
        var phase: CGFloat = 0
        if motionAllowed {
            let t = CGFloat((CACurrentMediaTime() - selectionStart) / 0.9)
            let cycle = t.truncatingRemainder(dividingBy: 2)
            let x = cycle < 1 ? cycle : 2 - cycle
            phase = 1 - (1 - x) * (1 - x)
        }
        let ringR = radius * (1.06 + 0.1 * phase)
        let ring = UIColor.token("sel_ring")
        strokeCircle(ctx, p, ringR, ring.withAlphaComponent(0.27 * (1 - phase * 0.5)), radius * 0.3)
        strokeCircle(ctx, p, ringR, ring, radius * 0.13)
        let corner = radius * 1.34, len = radius * 0.34
        ctx.setStrokeColor(ring.cgColor)
        ctx.setLineWidth(radius * 0.11)
        ctx.setLineCap(.round)
        for sx in [-1, 1] as [CGFloat] {
            for sy in [-1, 1] as [CGFloat] {
                let x = p.x + sx * corner, y = p.y + sy * corner
                line(ctx, CGPoint(x: x, y: y), CGPoint(x: x - sx * len, y: y))
                line(ctx, CGPoint(x: x, y: y), CGPoint(x: x, y: y - sy * len))
            }
        }
        ctx.setLineCap(.butt)
    }

    private func drawHint(_ ctx: CGContext) {
        guard let m = hintMove else { return }
        if hintGhostPiece != 0 { drawPiece(ctx, center(m.to), hintGhostPiece, alpha: 0.35) }
        let from = center(m.from), to = center(m.to)
        let dx = to.x - from.x, dy = to.y - from.y
        let len = hypot(dx, dy)
        guard len >= 1 else { return }
        let color = UIColor.token("hl_arrow")
        let head = radius * 0.72, tail = radius * 0.4
        let ux = dx / len, uy = dy / len
        ctx.setStrokeColor(color.cgColor)
        ctx.setLineWidth(radius * 0.17)
        ctx.setLineCap(.round)
        line(ctx, CGPoint(x: from.x + ux * tail, y: from.y + uy * tail), CGPoint(x: to.x - ux * head, y: to.y - uy * head))
        ctx.setLineCap(.butt)
        let angle = atan2(dy, dx), spread = CGFloat.pi * 28 / 180
        ctx.setFillColor(color.cgColor)
        ctx.move(to: to)
        ctx.addLine(to: CGPoint(x: to.x - head * cos(angle - spread), y: to.y - head * sin(angle - spread)))
        ctx.addLine(to: CGPoint(x: to.x - head * cos(angle + spread), y: to.y - head * sin(angle + spread)))
        ctx.closePath()
        ctx.fillPath()
    }

    private func drawTag(_ ctx: CGContext) {
        guard let tg = boardTag else { return }
        let t: CGFloat = tg.isStatic ? 0.18 : (tg.anim.progress(now) ?? 1)
        let appear = min(max(t / 0.12, 0), 1)
        let alpha = t > 0.75 ? 1 - (t - 0.75) / 0.25 : appear
        let rise = tg.isStatic ? 0 : radius * 1.9 * t
        let font = UIFont.systemFont(ofSize: radius * 0.56 * (0.75 + 0.25 * appear), weight: .bold)
        let str = NSAttributedString(string: tg.text, attributes: [.font: font])
        let pad = radius * 0.3
        let w = str.size().width + pad * 2
        let h = font.lineHeight + pad * 0.8
        let c = center(tg.square)
        var cy = c.y - radius * 1.7 - rise
        if cy - h < boardRect.minY { cy = c.y + radius * 1.7 + rise }
        let pill = UIBezierPath(roundedRect: CGRect(x: c.x - w / 2, y: cy - h / 2, width: w, height: h), cornerRadius: h / 2)
        ctx.setFillColor(tg.color.withAlphaComponent(alpha * 0.92).cgColor)
        ctx.addPath(pill.cgPath)
        ctx.fillPath()
        text(tg.text, font: font, color: UIColor.token("piece_text").withAlphaComponent(alpha), center: CGPoint(x: c.x, y: cy))
    }

    // MARK: Capture effect

    private func drawImage(_ ctx: CGContext, _ img: CGImage?, center c: CGPoint, width: CGFloat, rotation: CGFloat, alpha: CGFloat,
                           additive: Bool = false) {
        guard let img, alpha > 0, width > 0 else { return }
        let h = width * CGFloat(img.height) / CGFloat(img.width)
        ctx.saveGState()
        ctx.setAlpha(min(alpha, 1))
        if additive { ctx.setBlendMode(.plusLighter) }
        ctx.translateBy(x: c.x, y: c.y)
        ctx.rotate(by: rotation * .pi / 180)
        ctx.scaleBy(x: 1, y: -1)
        ctx.draw(img, in: CGRect(x: -width / 2, y: -h / 2, width: width, height: h))
        ctx.restoreGState()
    }

    private func sheetFrame(_ sheet: CGImage?, _ frame: Int, key: String) -> CGImage? {
        guard let sheet else { return nil }
        let k = "\(key)-\(frame)"
        if let cached = frameCache[k] { return cached }
        let fw = sheet.width / 5, fh = sheet.height / 4
        let img = sheet.cropping(to: CGRect(x: (frame % 5) * fw, y: (frame / 5) * fh, width: fw, height: fh))
        frameCache[k] = img
        return img
    }

    private func fxChar(_ ctx: CGContext, _ s: String, _ c: CGPoint, size: CGFloat, alpha: CGFloat, color: UIColor) {
        guard alpha > 0 else { return }
        let font = PieceFont.font(size)
        let edge = NSAttributedString(string: s, attributes: [.font: font, .strokeWidth: 9.0,
                                                              .strokeColor: UIColor.white.withAlphaComponent(0.7 * alpha)])
        let fill = NSAttributedString(string: s, attributes: [.font: font, .foregroundColor: color.withAlphaComponent(alpha)])
        let w = fill.size().width
        let y = c.y - (font.ascender - font.descender) / 2
        edge.draw(at: CGPoint(x: c.x - w / 2, y: y))
        fill.draw(at: CGPoint(x: c.x - w / 2, y: y))
    }

    private func drawCaptureFx(_ ctx: CGContext) {
        guard let f = fx, let t = f.anim.progress(now) else { return }
        let c = center(f.square)
        let piece = Piece.fromCode(f.piece)
        let main = piece.isRed ? palette.pieceRed : palette.pieceBlack
        let spark = UIColor.token("fx_spark")
        let isKing = piece.type == 1
        let label = { (p: Piece) in self.latinPieces ? String(p.fen).uppercased() : p.chinese }

        if isKing && t < 0.55 {
            ctx.setFillColor(spark.withAlphaComponent((1 - t / 0.55) * 0.23).cgColor)
            ctx.fill(boardRect)
        }
        if t > 0.78 {
            let p = min(max((t - 0.78) / 0.22, 0), 1)
            let a = 0.78 * min(p * 6, 1) * (1 - p)
            let w = isKing ? cell * (2.6 + 1.8 * p) : radius * 4.4 * f.cfg.splash * (0.55 + 0.45 * p)
            drawImage(ctx, isKing ? BoardUIView.goldImage : BoardUIView.splashImage, center: c, width: w, rotation: p * 24, alpha: a)
        }
        let sheet = isKing ? BoardUIView.goldSheet : BoardUIView.burstSheet
        let t0 = fxImpact - 0.06
        let sp = min(max((t - t0) / (0.88 - t0), 0), 1)
        let frame = min(max(Int(sp * 19), 0), 19)
        if t >= t0 {
            let boomW = boardRect.width * (isKing ? 1.25 + 0.35 * sp : 1.05 + 0.3 * sp)
            drawImage(ctx, sheetFrame(sheet, frame, key: isKing ? "g" : "b"), center: c, width: boomW, rotation: 0, alpha: 1,
                      additive: true)
        }
        if t >= fxImpact && t < fxImpact + 0.08 {
            let k = (t - fxImpact) / 0.08
            ctx.setFillColor(UIColor.white.withAlphaComponent(0.27 * (1 - k) * min(k * 6, 1)).cgColor)
            ctx.fill(boardRect)
        } else if t >= 0.4 && t < 0.55 {
            ctx.setFillColor(spark.withAlphaComponent(0.15 * sin((t - 0.4) / 0.15 * .pi)).cgColor)
            ctx.fill(boardRect)
        }
        if t >= fxImpact && t < 0.66 {
            let p = (t - fxImpact) / (0.66 - fxImpact)
            let reach = boardRect.width * 0.75 * (0.15 + 0.85 * p)
            strokeCircle(ctx, c, reach, spark.withAlphaComponent(0.75 * (1 - p) * min(p * 10, 1)), radius * 0.16 * (1 - p) + 1)
        }
        let charSize = radius * 1.7
        if t < fxSplit {
            let pop = t < fxImpact ? 0.5 + 0.5 * t / fxImpact : 1 + 0.09 * sin(min((t - fxImpact) / 0.08, 1) * .pi)
            fxChar(ctx, label(piece), c, size: charSize * pop, alpha: 1, color: main)
        } else {
            let p = min(max((t - fxSplit) / (1 - fxSplit), 0), 1)
            let ang = -32 * CGFloat.pi / 180
            let ux = cos(ang), uy = sin(ang), nx = -uy, ny = ux
            let sep = cell * 0.4 * p, drop = cell * 1.1 * p * p, rot = 16 * p * .pi / 180
            let a = 1 - p * p
            let L = cell * 1.3
            for side in [1, -1] as [CGFloat] {
                ctx.saveGState()
                ctx.translateBy(x: c.x + side * nx * sep, y: c.y + side * ny * sep + drop)
                ctx.rotate(by: side * rot)
                ctx.move(to: CGPoint(x: ux * L, y: uy * L))
                ctx.addLine(to: CGPoint(x: -ux * L, y: -uy * L))
                ctx.addLine(to: CGPoint(x: -ux * L + side * nx * L, y: -uy * L + side * ny * L))
                ctx.addLine(to: CGPoint(x: ux * L + side * nx * L, y: uy * L + side * ny * L))
                ctx.closePath()
                ctx.clip()
                fxChar(ctx, label(piece), .zero, size: charSize, alpha: a, color: main)
                ctx.restoreGState()
            }
        }
        for i in 0..<f.cfg.slashes {
            let ti = t - (0.30 + 0.055 * CGFloat(i))
            guard ti >= 0, ti <= 0.26 else { continue }
            let p = ti / 0.26
            drawImage(ctx, BoardUIView.slashImage, center: c, width: radius * (4.2 + 1.6 * p), rotation: -32 + 54 * CGFloat(i),
                      alpha: 0.94 * (1 - p * p))
        }
        if t < fxImpact && f.attacker != 0 {
            let k = t / fxImpact
            let attacker = Piece.fromCode(f.attacker)
            let dist = cell * 1.5 * (1 - k)
            fxChar(ctx, label(attacker), CGPoint(x: c.x - f.dx * dist, y: c.y - f.dy * dist), size: radius * 1.35,
                   alpha: 0.78 * k, color: attacker.isRed ? palette.pieceRed : palette.pieceBlack)
        }
        let st = min(max((t - fxImpact) / (1 - fxImpact), 0), 1)
        if st > 0 {
            let gravity = 1.6 * st * st
            for s in f.shards {
                let px = c.x + s.vx * radius * 2.6 * st
                let py = c.y + s.vy * radius * 2.6 * st + radius * gravity
                let half = radius * s.size * (1 - st * 0.5)
                ctx.saveGState()
                ctx.translateBy(x: px, y: py)
                ctx.rotate(by: s.spin * st * .pi / 180)
                ctx.setFillColor(s.color.withAlphaComponent(1 - st).cgColor)
                ctx.fill(CGRect(x: -half, y: -half, width: half * 2, height: half * 2))
                ctx.restoreGState()
            }
        }
    }
}

/// Deterministic generator for effect shards (same position -> same burst)
struct SeededGenerator: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed &+ 0x9E37_79B9_7F4A_7C15 }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
