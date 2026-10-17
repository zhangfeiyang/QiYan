import SwiftUI

/// Lets a view model drive the board imperatively (same calls as the Android BoardView).
final class BoardHandle {
    weak var view: BoardUIView?
    /// 视图还没建好时记下最后一次局面 (和上一步标记), 建好后补上
    var pending: (Position, Move?)?
    var pendingLastMove: Move?

    func setPosition(_ p: Position, animate: Move? = nil) {
        if let v = view { v.setPosition(p, animateMove: animate) } else { pending = (p.copy(), animate) }
    }

    /// 局面 + 上一步框 (复盘/残局用)
    func show(_ p: Position, animate: Move? = nil, lastMove: Move?) {
        setPosition(p, animate: animate)
        if let v = view { v.setLastMove(lastMove) } else { pendingLastMove = lastMove }
    }
}

/// SwiftUI wrapper; reads display settings (coordinates, skin, piece set, effects) itself so
/// every screen that shows a board agrees on them.
struct BoardView: UIViewRepresentable {
    let handle: BoardHandle
    var flipped = false
    var editMode = false
    var inputEnabled = true
    var onMove: ((Move) -> Void)?
    var onSelect: ((Int, [Move]) -> Void)?
    var onCellTap: ((Int) -> Void)?
    @ObservedObject private var settings = AppSettings.shared

    init(handle: BoardHandle, flipped: Bool = false, editMode: Bool = false, inputEnabled: Bool = true,
         onMove: ((Move) -> Void)? = nil, onSelect: ((Int, [Move]) -> Void)? = nil, onCellTap: ((Int) -> Void)? = nil) {
        self.handle = handle
        self.flipped = flipped
        self.editMode = editMode
        self.inputEnabled = inputEnabled
        self.onMove = onMove
        self.onSelect = onSelect
        self.onCellTap = onCellTap
    }

    func makeUIView(context: Context) -> BoardUIView {
        let v = BoardUIView()
        handle.view = v
        if let (p, m) = handle.pending {
            v.setPosition(p, animateMove: m)
            handle.pending = nil
        }
        if let m = handle.pendingLastMove {
            v.setLastMove(m)
            handle.pendingLastMove = nil
        }
        v.onSelectHaptic = { Feedback.shared.selectPiece() }
        return v
    }

    func updateUIView(_ v: BoardUIView, context: Context) {
        handle.view = v
        v.onMove = onMove
        v.onSelect = onSelect
        v.onCellTap = onCellTap
        v.editMode = editMode
        v.inputEnabled = inputEnabled
        if v.flipped != flipped { v.flipped = flipped }
        if v.showCoordinates != settings.showCoordinates { v.showCoordinates = settings.showCoordinates }
        v.captureFxEnabled = settings.captureFx
        let skin = BoardSkin(rawValue: settings.boardSkin) ?? .wood
        if v.skin != skin { v.skin = skin }
        if v.latinPieces != Notation.latinPieces { v.latinPieces = Notation.latinPieces }
        let river = L("board_river_text")
        if v.riverText != river { v.riverText = river }
        v.accessibilityLabel = L("title_game")
    }
}
