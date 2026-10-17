import AVFoundation
import CoreImage
import SwiftUI

/// 实时支招: 摄像头对着棋盘, 局面一变就重算, 箭头画在预览上.
/// 不逐帧识别 (上一帧没算完就丢), 连续两帧一致才认, 搜索卡死 0.6 秒.
struct LiveAdviceView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = LiveAdviceModel()
    @StateObject private var toaster = Toaster()
    @State private var pinchBase: CGFloat = 1
    @State private var visible = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: vm.session).ignoresSafeArea()
            GeometryReader { g in
                LiveOverlay(corners: vm.corners, srcW: vm.srcW, srcH: vm.srcH, move: vm.move,
                            viewSize: g.size, grow: vm.grow)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Button { dismiss() } label: {
                        Image("ic_back").renderingMode(.template).resizable().frame(width: 24, height: 24)
                            .foregroundStyle(.white).frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L("action_back"))
                    Text(L("live_title")).font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
                    Spacer()
                }
                .padding(.horizontal, 6).frame(height: 56)
                .background(LinearGradient(colors: [.black.opacity(0.55), .clear], startPoint: .top, endPoint: .bottom))
                Spacer()
                VStack(spacing: 10) {
                    HStack(spacing: 8) {
                        if vm.statusVisible {
                            Circle().fill(vm.statusColor).frame(width: 10, height: 10).scaleEffect(vm.dotPop ? 1.5 : 1)
                        }
                        Text(vm.status).font(.system(size: 14)).foregroundStyle(.white)
                        Spacer()
                        if vm.zoom > 1.01 {
                            Text(String(format: L("live_zoom"), vm.zoom)).font(.system(size: 14, weight: .bold))
                                .foregroundStyle(.white).padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Capsule().fill(.white.opacity(0.2)))
                        }
                    }
                    Segmented(items: [(Side.red, L("recognize_red_to_move")), (Side.black, L("recognize_black_to_move"))],
                              selected: vm.sideToMove) { vm.setSide($0) }
                }
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 18).fill(.black.opacity(0.55)))
                .padding(.horizontal, 12).padding(.bottom, 12)
            }
            if !vm.started {
                VStack(spacing: 12) {
                    ProgressView().tint(.white)
                    Text(L("live_starting")).font(.system(size: 14)).foregroundStyle(.white)
                }
                .padding(24)
                .background(RoundedRectangle(cornerRadius: 16).fill(.black.opacity(0.6)))
                .transition(.opacity)
            }
        }
        .gesture(MagnificationGesture()
            .onChanged { vm.setZoom(pinchBase * $0) }
            .onEnded { _ in pinchBase = vm.zoom })
        .onTapGesture(count: 2) { vm.setZoom(1); pinchBase = 1 }
        .toast(toaster)
        .onAppear {
            visible = true
            vm.onFatal = { key in
                toaster.show(L(key))
                // 用户可能已经自己返回了: 那时再 dismiss 会误关别的页
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { if visible { dismiss() } }
            }
            vm.start()
        }
        .onDisappear {
            visible = false
            vm.stop()
        }
        .statusBarHidden(false)
        .preferredColorScheme(.dark)
    }
}

/// 预览层 (aspect fill, 与叠加层的映射同一算法)
private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.previewLayer.session = session
        v.previewLayer.videoGravity = .resizeAspectFill
        v.backgroundColor = .black
        return v
    }

    func updateUIView(_ v: PreviewView, context: Context) {}
}

/// 识别出的棋盘四边 + 推荐着法箭头
private struct LiveOverlay: View, Animatable {
    let corners: [Double]?
    let srcW: Double, srcH: Double
    let move: Move?
    let viewSize: CGSize
    var grow: Double

    var animatableData: Double {
        get { grow }
        set { grow = newValue }
    }

    var body: some View {
        Canvas { ctx, size in
            guard let c = corners, c.count == 8, srcW > 0, srcH > 0,
                  let h = Homography(from: [0, 0, 8, 0, 0, 9, 8, 9], to: c) else { return }
            func point(_ col: Double, _ row: Double) -> CGPoint {
                let (x, y) = h.apply(col, row)
                let (vx, vy) = LiveGeometry.srcToView(x: x, y: y, srcW: srcW, srcH: srcH,
                                                      viewW: Double(size.width), viewH: Double(size.height))
                return CGPoint(x: vx, y: vy)
            }
            var quad = Path()
            quad.move(to: point(0, 0))
            quad.addLine(to: point(8, 0))
            quad.addLine(to: point(8, 9))
            quad.addLine(to: point(0, 9))
            quad.closeSubpath()
            ctx.stroke(quad, with: .color(Theme.affirm.opacity(0.6)), lineWidth: 2)

            guard let m = move else { return }
            let from = point(Double(Square.col(m.from)), Double(Square.row(m.from)))
            let full = point(Double(Square.col(m.to)), Double(Square.row(m.to)))
            let to = CGPoint(x: from.x + (full.x - from.x) * grow, y: from.y + (full.y - from.y) * grow)
            let o = point(0, 0), o1 = point(1, 0)
            let cell = max(hypot(o1.x - o.x, o1.y - o.y), 8)
            let arrow = Theme.c("hl_arrow")
            ctx.stroke(Path(ellipseIn: CGRect(x: from.x - cell * 0.42, y: from.y - cell * 0.42, width: cell * 0.84, height: cell * 0.84)),
                       with: .color(arrow), lineWidth: cell * 0.10)
            let ang = atan2(to.y - from.y, to.x - from.x)
            let shaft = cell * 0.15, headLen = cell * 0.62, headW = cell * 0.30
            let base = CGPoint(x: to.x - cos(ang) * headLen, y: to.y - sin(ang) * headLen)
            func head(_ w: CGFloat) -> Path {
                var p = Path()
                p.move(to: to)
                p.addLine(to: CGPoint(x: base.x - sin(ang) * w, y: base.y + cos(ang) * w))
                p.addLine(to: CGPoint(x: base.x + sin(ang) * w, y: base.y - cos(ang) * w))
                p.closeSubpath()
                return p
            }
            var line = Path()
            line.move(to: from)
            line.addLine(to: base)
            let edge = Color.black.opacity(0.8)
            ctx.stroke(line, with: .color(edge), style: StrokeStyle(lineWidth: shaft * 1.7, lineCap: .round))
            ctx.fill(head(headW * 1.35), with: .color(edge))
            ctx.stroke(line, with: .color(arrow), style: StrokeStyle(lineWidth: shaft, lineCap: .round))
            ctx.fill(head(headW), with: .color(arrow))
        }
    }
}

final class LiveAdviceModel: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private static let stableFrames = 2
    private static let movetime = 600

    let session = AVCaptureSession()
    @Published private(set) var started = false
    @Published private(set) var corners: [Double]?
    @Published private(set) var srcW: Double = 0
    @Published private(set) var srcH: Double = 0
    @Published private(set) var move: Move?
    @Published private(set) var grow: Double = 1
    @Published private(set) var status = L("live_locking")
    @Published private(set) var statusColor = Theme.warning
    @Published private(set) var statusVisible = false
    @Published private(set) var dotPop = false
    @Published private(set) var zoom: CGFloat = 1
    @Published private(set) var sideToMove = Side.red
    var onFatal: ((String) -> Void)?

    private let queue = DispatchQueue(label: "qiyan.live.frames")
    private let ciContext = CIContext()
    private var recognizer: BoardRecognizer?
    private var device: AVCaptureDevice?
    private let gate = StabilityGate(needed: stableFrames)
    private var recognizing = false     // queue only
    private var searching = false       // main only
    private var wasLocked = false       // queue only
    private var side = Side.red         // queue copy of sideToMove
    private var configured = false

    func start() {
        DispatchQueue.global(qos: .userInitiated).async {
            let r = BoardRecognizer.create()
            DispatchQueue.main.async {
                guard let r else { self.onFatal?("recognize_model_failed"); return }
                self.recognizer = r
                // 没有后置相机 (模拟器/部分 iPad) 就别弹权限框了
                guard AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil else {
                    self.onFatal?("live_camera_failed")
                    return
                }
                // 模型好了再问相机权限, 免得先弹权限框再报模型失败
                switch AVCaptureDevice.authorizationStatus(for: .video) {
                case .authorized: self.startCamera()
                case .notDetermined:
                    AVCaptureDevice.requestAccess(for: .video) { ok in
                        DispatchQueue.main.async { ok ? self.startCamera() : self.onFatal?("live_need_camera") }
                    }
                default: self.onFatal?("live_need_camera")
                }
            }
        }
    }

    private func startCamera() {
        queue.async {
            if !self.configured {
                guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                      let input = try? AVCaptureDeviceInput(device: dev) else {
                    DispatchQueue.main.async { self.onFatal?("live_camera_failed") }
                    return
                }
                self.session.beginConfiguration()
                // 识别自己就要缩到 256/400, 720p 足够
                self.session.sessionPreset = .hd1280x720
                let output = AVCaptureVideoDataOutput()
                output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                output.alwaysDiscardsLateVideoFrames = true
                output.setSampleBufferDelegate(self, queue: self.queue)
                guard self.session.canAddInput(input), self.session.canAddOutput(output) else {
                    self.session.commitConfiguration()
                    DispatchQueue.main.async { self.onFatal?("live_camera_failed") }
                    return
                }
                self.session.addInput(input)
                self.session.addOutput(output)
                if let conn = output.connection(with: .video), conn.isVideoRotationAngleSupported(90) {
                    conn.videoRotationAngle = 90     // 竖屏: 帧直接是正的
                }
                self.session.commitConfiguration()
                self.device = dev
                self.configured = true
            }
            self.session.startRunning()
        }
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
        PikafishEngine.shared.stop()
    }

    func setSide(_ s: Int) {
        sideToMove = s
        move = nil
        queue.async {
            self.side = s
            self.gate.invalidate()
        }
    }

    func setZoom(_ z: CGFloat) {
        guard let d = device else { return }
        let v = min(max(z, 1), min(d.activeFormat.videoMaxZoomFactor, 8))
        do {
            try d.lockForConfiguration()
            d.videoZoomFactor = v
            d.unlockForConfiguration()
            zoom = v
        } catch {}
    }

    // MARK: Frames (queue)

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if !started_ { started_ = true; DispatchQueue.main.async { withAnimation { self.started = true } } }
        guard !recognizing, let r = recognizer, let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        recognizing = true
        defer { recognizing = false }
        let start = Date()
        let ci = CIImage(cvPixelBuffer: pixels)
        guard let cg = ciContext.createCGImage(ci, from: ci.extent), let result = r.recognize(cg) else { return }
        let cost = Int(Date().timeIntervalSince(start) * 1000)
        let w = Double(cg.width), h = Double(cg.height)

        // 四角没框住时别画: 角点一飘箭头会指到棋盘外
        if result.meanCornerScore < BoardLayout.lowCornerScore {
            _ = gate.offer(nil)
            wasLocked = false
            DispatchQueue.main.async {
                self.corners = nil
                self.move = nil
                self.setStatus(ok: false, locked: false, cost: cost, justLocked: false)
            }
            return
        }
        let ok = !BoardLayout.validate(result.layout, sideToMove: side).contains { $0.blocking }
        let fen = ok ? BoardLayout.toFen(result.layout, sideToMove: side) : nil
        let changed = gate.offer(fen)
        let locked = gate.accepted != nil
        let justLocked = locked && ok && !wasLocked
        if justLocked { Feedback.shared.liveLock() }
        wasLocked = locked && ok
        DispatchQueue.main.async {
            self.corners = result.corners
            self.srcW = w
            self.srcH = h
            self.setStatus(ok: ok, locked: locked, cost: cost, justLocked: justLocked)
            if changed, let fen { self.requestMove(fen) }
        }
    }
    private var started_ = false

    // MARK: Main

    private func setStatus(ok: Bool, locked: Bool, cost: Int, justLocked: Bool) {
        statusVisible = true
        statusColor = !ok ? Theme.textTertiary : (!locked ? Theme.warning : Theme.affirm)
        status = !ok ? L("live_board_invalid") : (!locked ? L("live_locking") : L("live_ready", cost))
        if justLocked, AppSettings.shared.motionAllowed {
            withAnimation(.easeOut(duration: 0.1)) { dotPop = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { withAnimation(.easeIn(duration: 0.1)) { self.dotPop = false } }
        }
    }

    private func requestMove(_ fen: String) {
        guard !searching else { return }
        searching = true
        let params = SearchParams(movetime: Self.movetime, threads: 2, hashMb: 64, multiPv: 1, strictMovetime: true)
        PikafishEngine.shared.search(fen: fen, moves: [], params: params) { [weak self] r in
            guard let self else { return }
            self.searching = false
            guard let uci = r.bestMove, let m = UciCoords.parse(uci), m.from != m.to else { return }
            if m != self.move, AppSettings.shared.motionAllowed {
                self.grow = 0
                self.move = m
                withAnimation(.easeOut(duration: 0.26)) { self.grow = 1 }
            } else {
                self.move = m
            }
        }
    }
}
