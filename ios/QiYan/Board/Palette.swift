import SwiftUI
import UIKit

/// Named colors from the generated asset catalog (Android color tokens, light + dark).
extension UIColor {
    static func token(_ name: String) -> UIColor { UIColor(named: name) ?? .magenta }

    convenience init(argb: UInt32) {
        self.init(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                  blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }
}

extension Color {
    static func token(_ name: String) -> Color { Color(name) }
}

/// 棋盘材质: 黄花梨跟主题色走, 另外三套是盘面自己的颜色 (与安卓 BoardSkin 一致)
enum BoardSkin: Int, CaseIterable, Identifiable {
    case wood = 0, blackGold, porcelain, jade
    var id: Int { rawValue }
    var nameKey: String { ["skin_wood", "skin_black_gold", "skin_porcelain", "skin_jade"][rawValue] }
}

struct BoardPalette {
    let frameTop, frameEdge, grain, line, palace, river, border, coord: UIColor
    let boardGrad: [UIColor]
    let pieceFace: UIColor
    let pieceGrad: [UIColor]
    let pieceShade, pieceRed, pieceBlack, pieceRedEdge, pieceBlackEdge: UIColor

    static func of(_ skin: BoardSkin) -> BoardPalette {
        switch skin {
        case .wood:
            let t = UIColor.token
            return BoardPalette(frameTop: t("board_frame"), frameEdge: t("board_frame_edge"), grain: t("board_grain"),
                                line: t("board_line"), palace: t("board_line"), river: t("board_palace"),
                                border: t("board_border"), coord: t("board_coord"),
                                boardGrad: [t("board_grad_1"), t("board_grad_2"), t("board_grad_3"), t("board_grad_4")],
                                pieceFace: t("piece_face"), pieceGrad: [t("piece_grad_1"), t("piece_grad_2"), t("piece_grad_3")],
                                pieceShade: t("piece_shade"), pieceRed: t("piece_red"), pieceBlack: t("piece_black"),
                                pieceRedEdge: t("piece_red_edge"), pieceBlackEdge: t("piece_black_edge"))
        case .blackGold:
            return make(0xFF4A3020, 0xFF140C08, 0x33E7C27A, 0xFFE7C27A, 0xFFD4B483, 0xFFF0D7A0, 0xFFC6A15A, 0xFFE8D2A2,
                        [0xFF3A2618, 0xFF2C1B12, 0xFF24150E, 0xFF1A100C], 0xFFF6E7C8, [0xFFFFF6E4, 0xFFF3E0BE, 0xFFE4C99A],
                        0x66A67C52, 0xFFC23A2E, 0xFF1A120C, 0xFF8E241C, 0xFF000000)
        case .porcelain:
            return make(0xFF2A4E78, 0xFF0E2240, 0x223A6EA5, 0xFF1A3E66, 0xFF1A3E66, 0xFF16365C, 0xFF16365C, 0xFF1A3E66,
                        [0xFFF7F9FC, 0xFFE7EEF6, 0xFFF4F1E8, 0xFFE3EAF3], 0xFFFBF8F1, [0xFFFFFFFF, 0xFFF4EFE4, 0xFFE7DDD0],
                        0x5590A4B8, 0xFFA32020, 0xFF12304F, 0xFF7A1E18, 0xFF0B2038)
        case .jade:
            return make(0xFFE7E2D6, 0xFFB7AD9C, 0x226E624F, 0xFF6B5E4A, 0xFF5C5344, 0xFF5C5344, 0xFF8A7B66, 0xFF5C5344,
                        [0xFFFBF8F1, 0xFFF3EBDD, 0xFFEFE6D6, 0xFFE6DCC8], 0xFFFFFCF6, [0xFFFFFFFF, 0xFFF7F1E6, 0xFFEADFCB],
                        0x44C4B59A, 0xFFB42318, 0xFF241C16, 0xFF7E1812, 0xFF120E0C)
        }
    }

    private static func make(_ frameTop: UInt32, _ frameEdge: UInt32, _ grain: UInt32, _ line: UInt32, _ palace: UInt32,
                             _ river: UInt32, _ border: UInt32, _ coord: UInt32, _ boardGrad: [UInt32], _ face: UInt32,
                             _ pieceGrad: [UInt32], _ shade: UInt32, _ red: UInt32, _ black: UInt32, _ redEdge: UInt32,
                             _ blackEdge: UInt32) -> BoardPalette {
        let c = { UIColor(argb: $0) }
        return BoardPalette(frameTop: c(frameTop), frameEdge: c(frameEdge), grain: c(grain), line: c(line), palace: c(palace),
                            river: c(river), border: c(border), coord: c(coord), boardGrad: boardGrad.map(c),
                            pieceFace: c(face), pieceGrad: pieceGrad.map(c), pieceShade: c(shade), pieceRed: c(red),
                            pieceBlack: c(black), pieceRedEdge: c(redEdge), pieceBlackEdge: c(blackEdge))
    }
}

/// 棋子与题字的楷体: 霞鹜文楷 Medium 子集 (OFL)
enum PieceFont {
    static func font(_ size: CGFloat) -> UIFont {
        UIFont(name: "LXGWWenKai-Medium", size: size) ?? UIFont.systemFont(ofSize: size, weight: .bold)
    }
}
