import PhotosUI
import SwiftUI

/// 拍照/选图识局, 人工核对改错, 再让引擎支招.
/// 模型整盘全对约 83%: 可疑格高亮, 托盘摆子改错, 局面不合法时不求招.
struct RecognizeView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = RecognizeViewModel()
    @StateObject private var toaster = Toaster()
    @State private var pickerItem: PhotosPickerItem?
    @State private var showGallery = false
    @State private var showCamera = false
    @State private var showLevels = false

    var body: some View {
        ZStack {
            PageBackground()
            VStack(spacing: 0) {
                TopBar(title: L("title_recognize"), onBack: { dismiss() }) {
                    HStack(spacing: 0) {
                        iconButton("ic_image", L("recognize_pick_image")) { showGallery = true }
                        iconButton("ic_camera", L("recognize_take_photo")) { openCamera() }
                    }
                    .disabled(!vm.modelReady || vm.busy)
                }
                if vm.hasBoard { content } else { startPanel }
            }
            if vm.busy { ScanOverlay(title: L(vm.busyTitle), subtitle: vm.busySubtitle) }
        }
        .toast(toaster)
        .onAppear {
            vm.onToast = { toaster.show($0) }
            vm.loadModel()
        }
        .photosPicker(isPresented: $showGallery, selection: $pickerItem, matching: .images)
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            pickerItem = nil
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let img = BoardRecognizer.loadImage(data) {
                    vm.recognize(img)
                } else {
                    toaster.show(L("recognize_failed"))
                }
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                showCamera = false
                if let image, let cg = BoardRecognizer.loadImage(image) { vm.recognize(cg) }
            }
            .ignoresSafeArea()
        }
        .confirmationDialog(L("advice_level_title"), isPresented: $showLevels, titleVisibility: .visible) {
            ForEach(RecognizeViewModel.AdviceLevel.allCases, id: \.self) { l in
                Button(L(l.labelKey) + " · " + L(l.descKey)) { vm.adviceLevel = l }
            }
            Button(L("dialog_cancel"), role: .cancel) {}
        }
    }

    private func openCamera() {
        if UIImagePickerController.isSourceTypeAvailable(.camera) { showCamera = true }
        else { toaster.show(L("recognize_no_camera")) }
    }

    private func iconButton(_ icon: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(icon).renderingMode(.template).resizable().frame(width: 24, height: 24)
                .foregroundStyle(Theme.textPrimary).frame(width: 44, height: 44)
        }
        .accessibilityLabel(label)
    }

    // MARK: Start panel

    private var startPanel: some View {
        ScrollView {
            VStack(spacing: 0) {
                Spacer(minLength: 120)
                Text(L("recognize_start_title")).font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.textPrimary)
                    .padding(.bottom, 24)
                Button { openCamera() } label: { Label(L("recognize_take_photo"), image: "ic_camera") }
                    .buttonStyle(GoldButtonStyle(height: 56))
                    .disabled(!vm.modelReady)
                Text(L("recognize_start_camera_desc")).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                    .padding(.top, 8).padding(.bottom, 24)
                Button { showGallery = true } label: { Label(L("recognize_pick_image"), image: "ic_image") }
                    .buttonStyle(OutlineButtonStyle(height: 50, fullWidth: true))
                    .disabled(!vm.modelReady)
                Text(L("recognize_start_gallery_desc")).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                    .padding(.top, 8)
                if !vm.modelReady {
                    HStack(spacing: 8) {
                        if vm.modelFailed {
                            Button { vm.loadModel() } label: { Label(L("action_retry"), image: "ic_refresh") }
                                .buttonStyle(OutlineButtonStyle())
                        } else {
                            ProgressView()
                            Text(L("recognize_loading_model")).font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
                        }
                    }
                    .padding(.top, 16)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("recognize_tips_title")).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.textPrimary)
                        .padding(.bottom, 6)
                    ForEach(["recognize_tip_1", "recognize_tip_2", "recognize_tip_3"], id: \.self) {
                        Text(L($0)).font(.system(size: 14)).foregroundStyle(Theme.textSecondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
                .card(radius: 14)
                .padding(.top, 28)
            }
            .padding(.horizontal, 20)
        }
    }

    // MARK: Board editing

    private var content: some View {
        VStack(spacing: 6) {
            tray(red: false)
            BoardView(handle: vm.board, flipped: vm.flipped, editMode: true, onCellTap: { vm.boardTap($0) })
                .frame(maxHeight: .infinity)
            tray(red: true)
            HStack(spacing: 10) {
                Segmented(items: [(Side.red, L("recognize_red_to_move")), (Side.black, L("recognize_black_to_move"))],
                          selected: vm.sideToMove) { vm.sideToMove = $0 }
                Button { vm.flipped.toggle() } label: {
                    Label(L("recognize_flip"), image: "ic_flip").labelStyle(.titleAndIcon)
                }
                .buttonStyle(OutlineButtonStyle(height: 46))
                .fixedSize()
            }
            .padding(.horizontal, 16)
            if let advice = vm.advice {
                Text(advice).font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
            }
            HStack(spacing: 10) {
                Button { showLevels = true } label: {
                    HStack(spacing: 4) {
                        Text(L("advice_level_current", L(vm.adviceLevel.labelKey))).lineLimit(1).minimumScaleFactor(0.8)
                        Image("ic_chevron_down").renderingMode(.template).resizable().frame(width: 14, height: 14)
                    }
                }
                .buttonStyle(OutlineButtonStyle(height: 50))
                .fixedSize()
                Button { vm.requestAdvice() } label: { Label(L("recognize_advise"), image: "ic_ai") }
                    .buttonStyle(GoldButtonStyle(height: 50))
                    .disabled(vm.thinking)
            }
            .padding(.horizontal, 16).padding(.bottom, 8)
        }
    }

    private func tray(red: Bool) -> some View {
        let chars: [Character] = red ? ["K", "A", "B", "N", "R", "C", "P"] : ["k", "a", "b", "n", "r", "c", "p"]
        return HStack(spacing: 6) {
            ForEach(chars, id: \.self) { ch in
                let piece = Piece.fromFen(ch)
                let on = vm.heldTray == ch
                Button { vm.trayTap(ch) } label: {
                    Text(Notation.pieceLabel(piece)).font(Font(PieceFont.font(19))).bold()
                        .foregroundStyle(Theme.c(red ? "piece_red" : "piece_black"))
                        .frame(width: 38, height: 38)
                        .background(Circle().fill(Theme.c("piece_face")))
                        .overlay(Circle().stroke(on ? Theme.action : Theme.c(red ? "piece_red" : "piece_black").opacity(0.6),
                                                 lineWidth: on ? 3 : 1.2))
                        .scaleEffect(on ? 1.1 : 1)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Names.piece(piece))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
            Button { vm.trashTap() } label: {
                Image(systemName: "xmark").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.textTertiary)
                    .frame(width: 38, height: 38)
                    .overlay(Circle().stroke(Theme.textTertiary, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3])))
            }
            .buttonStyle(.plain)
            .padding(.leading, 6)
            .opacity(vm.heldSquare >= 0 ? 1 : 0.4)
            .accessibilityLabel(L("recognize_empty"))
        }
        .frame(height: 44)
    }
}

/// 识别/求招时的扫描层: 暗底 + 一道扫过的光 + 标题
struct ScanOverlay: View {
    let title: String
    var subtitle: String?
    @State private var phase: CGFloat = 0

    var body: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea()
            GeometryReader { g in
                LinearGradient(colors: [.clear, Theme.c("fx_spark").opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
                    .frame(height: 120)
                    .offset(y: phase * (g.size.height + 120) - 120)
            }
            .ignoresSafeArea()
            VStack(spacing: 10) {
                ProgressView().tint(.white).scaleEffect(1.3)
                Text(title).font(.system(size: 19, weight: .bold)).foregroundStyle(.white)
                if let subtitle { Text(subtitle).font(.system(size: 14)).foregroundStyle(.white.opacity(0.8)) }
            }
        }
        .transition(.opacity)
        .onAppear {
            guard AppSettings.shared.motionAllowed else { return }
            withAnimation(.linear(duration: 1.6).repeatForever(autoreverses: false)) { phase = 1 }
        }
        .accessibilityElement(children: .combine)
    }
}

/// 系统相机
struct CameraPicker: UIViewControllerRepresentable {
    let onDone: (UIImage?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let p = UIImagePickerController()
        p.sourceType = .camera
        p.delegate = context.coordinator
        return p
    }

    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onDone) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onDone: (UIImage?) -> Void
        init(_ onDone: @escaping (UIImage?) -> Void) { self.onDone = onDone }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            onDone(info[.originalImage] as? UIImage)
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { onDone(nil) }
    }
}

@MainActor
final class RecognizeViewModel: ObservableObject {
    enum AdviceLevel: CaseIterable {
        case fast, normal, deep
        var labelKey: String { ["advice_level_fast", "advice_level_normal", "advice_level_deep"][index] }
        var descKey: String { ["advice_level_fast_desc", "advice_level_normal_desc", "advice_level_deep_desc"][index] }
        var movetime: Int { [800, 1600, 3200][index] }
        private var index: Int { AdviceLevel.allCases.firstIndex(of: self)! }
    }

    @Published private(set) var modelReady = false
    @Published private(set) var modelFailed = false
    @Published private(set) var busy = false
    @Published private(set) var busyTitle = "analysis_scanning_recognize"
    @Published private(set) var busySubtitle: String?
    @Published private(set) var hasBoard = false
    @Published private(set) var thinking = false
    @Published private(set) var advice: String?
    @Published private(set) var heldTray: Character?
    @Published private(set) var heldSquare = -1
    @Published var flipped = false
    @Published var sideToMove = Side.red { didSet { refresh() } }
    @Published var adviceLevel = AdviceLevel.normal
    var onToast: ((String) -> Void)?

    let board = BoardHandle()
    private var recognizer: BoardRecognizer?
    private var layout = BoardLayout.emptyLayout()
    private var confidence: [[Float]]?
    private var blocking: [String] = []
    private var loading = false

    func loadModel() {
        guard recognizer == nil, !loading else { return }
        loading = true
        modelFailed = false
        DispatchQueue.global(qos: .userInitiated).async {
            let r = BoardRecognizer.create()
            DispatchQueue.main.async {
                self.loading = false
                self.recognizer = r
                self.modelReady = r != nil
                self.modelFailed = r == nil
                if r == nil { self.onToast?(L("recognize_model_failed")) }
                #if DEBUG
                if r != nil, let path = UserDefaults.standard.string(forKey: "recognizeFile"),
                   let data = FileManager.default.contents(atPath: path), let img = BoardRecognizer.loadImage(data) {
                    self.recognize(img)
                }
                #endif
            }
        }
    }

    func recognize(_ image: CGImage) {
        guard let r = recognizer, !busy else { return }
        Feedback.shared.recognizeStart()
        busyTitle = "analysis_scanning_recognize"
        busySubtitle = nil
        withAnimation { busy = true }
        advice = nil
        DispatchQueue.global(qos: .userInitiated).async {
            let result = r.recognize(image)
            DispatchQueue.main.async {
                withAnimation { self.busy = false }
                guard let result else {
                    Feedback.shared.recognizeFail()
                    self.onToast?(L("recognize_failed"))
                    return
                }
                Feedback.shared.recognizeOk()
                #if DEBUG
                let text = result.layout.map { String($0) }.joined(separator: "\n")
                try? text.write(toFile: NSTemporaryDirectory() + "qy_vision_out.txt", atomically: true, encoding: .utf8)
                try? text.write(toFile: "/tmp/qy_vision_out.txt", atomically: true, encoding: .utf8)
                #endif
                self.layout = result.layout
                self.confidence = result.confidence
                self.hasBoard = true
                // 按原照片的上下关系显示: 从红方那侧拍过去就让红方在上
                self.flipped = result.redOnTop
                self.heldTray = nil
                self.heldSquare = -1
                if result.meanCornerScore < BoardLayout.lowCornerScore || result.offBoardCount > 0 {
                    self.onToast?(L("recognize_corner_warning"))
                }
                self.refresh()
            }
        }
    }

    private func refresh() {
        let pos = BoardLayout.toPosition(layout, sideToMove: sideToMove) ?? {
            let p = Position()
            p.clear()
            return p
        }()
        board.show(pos, lastMove: nil)
        board.view?.setSelectedSquare(heldSquare)
        let issues = BoardLayout.validate(layout, sideToMove: sideToMove)
        var suspect = Set<Int>()
        if let c = confidence { BoardLayout.lowConfidenceCells(c).forEach { suspect.insert(Square.make($0.row, $0.col)) } }
        issues.forEach { $0.cells.forEach { suspect.insert(Square.make($0.row, $0.col)) } }
        board.view?.setSuspect(suspect)
        blocking = issues.filter(\.blocking).map { $0.message(chinese: AppLocale.isChinese) }
    }

    // MARK: Editing

    func trayTap(_ ch: Character) {
        heldTray = heldTray == ch ? nil : ch
        heldSquare = -1
        refresh()
    }

    func trashTap() {
        guard heldSquare >= 0 else { return }
        setCell(heldSquare, BoardLayout.empty)
        heldSquare = -1
        refresh()
    }

    func boardTap(_ sq: Int) {
        if let ch = heldTray {
            setCell(sq, ch)
            heldTray = nil
        } else if heldSquare >= 0 {
            if heldSquare != sq {
                let moving = cell(heldSquare)
                setCell(heldSquare, BoardLayout.empty)
                setCell(sq, moving)
            }
            heldSquare = -1
        } else {
            heldSquare = cell(sq) != BoardLayout.empty ? sq : -1
        }
        advice = nil
        refresh()
    }

    private func cell(_ sq: Int) -> Character { layout[Square.row(sq)][Square.col(sq)] }

    private func setCell(_ sq: Int, _ ch: Character) {
        layout[Square.row(sq)][Square.col(sq)] = ch
        // 用户亲手摆的, 不再标成可疑
        confidence?[Square.row(sq)][Square.col(sq)] = 1
    }

    // MARK: Advice

    func requestAdvice() {
        if !blocking.isEmpty {
            onToast?(L("recognize_must_fix") + " " + blocking.joined(separator: "; "))
            return
        }
        let fen = BoardLayout.toFen(layout, sideToMove: sideToMove)
        thinking = true
        advice = L("recognize_thinking")
        busyTitle = "analysis_scanning_advice"
        busySubtitle = L("recognize_thinking")
        withAnimation { busy = true }
        let params = SearchParams(movetime: adviceLevel.movetime, threads: min(AppSettings.shared.threads, 4),
                                  hashMb: min(AppSettings.shared.hashMb, 128), multiPv: 1, strictMovetime: true)
        PikafishEngine.shared.search(fen: fen, moves: [], params: params) { [weak self] r in
            guard let self else { return }
            self.thinking = false
            withAnimation { self.busy = false }
            guard let uci = r.bestMove, let m = UciCoords.parse(uci), m.from != m.to else {
                Feedback.shared.recognizeFail()
                self.advice = L(r.bestMove == nil ? "recognize_engine_failed" : "recognize_no_move")
                return
            }
            Feedback.shared.recognizeOk()
            let pos = BoardLayout.toPosition(self.layout, sideToMove: self.sideToMove)
            let piece = pos.map { Piece.fromCode($0.pieceAt(m.from)) } ?? .none
            self.advice = L("recognize_advice", piece == .none ? "" : Notation.pieceLabel(piece),
                            UciCoords.squareToUci(m.from), UciCoords.squareToUci(m.to))
            self.board.view?.setHint(m)
        }
    }

    deinit { PikafishEngine.shared.stop() }
}
