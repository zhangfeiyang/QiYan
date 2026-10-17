import Foundation

/// 识局流水线里与图像库无关的数学部分 (安卓 BoardRecognizer 的同一套常量与解码).
/// 模型来自 TheOne1006/chinese-chess-recognition (MIT).
enum VisionMath {
    static let poseSize = 256
    static let posePadding: Double = 1.25
    static let simccBins: Double = 512
    static let warpW = 400, warpH = 450
    static let warpPad: Double = 25
    static let clsW = 280, clsH = 315

    // ImageNet 归一化, 两个模型共用
    static let mean: [Float] = [123.675, 116.28, 103.53]
    static let std: [Float] = [58.395, 57.12, 57.375]

    /// 关键点模型的输入: 以图片中心为心, 边长 max(w,h)*1.25 的正方形缩放进 256x256
    struct PoseFrame {
        let cx: Double, cy: Double, k: Double
        init(width: Double, height: Double) {
            cx = width / 2
            cy = height / 2
            k = Double(VisionMath.poseSize) / (max(width, height) * VisionMath.posePadding)
        }
        /// 原图 -> 256 空间
        func toInput(_ x: Double, _ y: Double) -> (Double, Double) {
            let half = Double(VisionMath.poseSize) / 2
            return ((x - cx) * k + half, (y - cy) * k + half)
        }
        /// 256 空间 -> 原图
        func toSource(_ x: Double, _ y: Double) -> (Double, Double) {
            let half = Double(VisionMath.poseSize) / 2
            return ((x - half) / k + cx, (y - half) / k + cy)
        }
    }

    static func argmax(_ v: UnsafeBufferPointer<Float>) -> (Int, Float) {
        var bi = 0, bv = v[0]
        for i in 1..<v.count where v[i] > bv { bv = v[i]; bi = i }
        return (bi, bv)
    }

    /// SimCC (1,4,512) x/y -> 四角原图坐标 [A0, A8, J0, J8] 与得分
    static func decodeCorners(simccX: [Float], simccY: [Float], frame: PoseFrame) -> (corners: [Double], scores: [Float]) {
        let n = simccX.count / Int(simccBins)
        var corners = [Double](repeating: 0, count: n * 2)
        var scores = [Float](repeating: 0, count: n)
        let bins = Int(simccBins)
        for i in 0..<n {
            let (xi, xv) = simccX.withUnsafeBufferPointer { argmax(UnsafeBufferPointer(rebasing: $0[i * bins..<(i + 1) * bins])) }
            let (yi, yv) = simccY.withUnsafeBufferPointer { argmax(UnsafeBufferPointer(rebasing: $0[i * bins..<(i + 1) * bins])) }
            let (x, y) = frame.toSource(Double(xi) / simccBins * Double(poseSize), Double(yi) / simccBins * Double(poseSize))
            corners[i * 2] = x
            corners[i * 2 + 1] = y
            scores[i] = xv * yv
        }
        return (corners, scores)
    }

    /// 校正图里四角的目标位置: 400x450, 四周留白 25
    static var warpTargets: [Double] {
        let w = Double(warpW), h = Double(warpH), p = warpPad
        return [p, p, w - p, p, p, h - p, w - p, h - p]
    }

    /// 分类输出 (1,90,16, 已 softmax) -> 10x9 排布与置信度
    static func decodeLayout(_ probs: [Float]) -> (layout: [[Character]], confidence: [[Float]]) {
        var layout = BoardLayout.emptyLayout()
        var conf = [[Float]](repeating: [Float](repeating: 0, count: BoardLayout.cols), count: BoardLayout.rows)
        let classes = BoardLayout.classChars.count
        probs.withUnsafeBufferPointer { buf in
            for i in 0..<(BoardLayout.rows * BoardLayout.cols) {
                let (idx, p) = argmax(UnsafeBufferPointer(rebasing: buf[i * classes..<(i + 1) * classes]))
                layout[i / BoardLayout.cols][i % BoardLayout.cols] = BoardLayout.classChars[idx]
                conf[i / BoardLayout.cols][i % BoardLayout.cols] = p
            }
        }
        return (layout, conf)
    }

    /// RGBA8 像素 -> NCHW float (ImageNet 归一化)
    static func normalize(rgba: UnsafePointer<UInt8>, width: Int, height: Int, bytesPerRow: Int) -> [Float] {
        let plane = width * height
        var out = [Float](repeating: 0, count: plane * 3)
        out.withUnsafeMutableBufferPointer { o in
            for y in 0..<height {
                let row = rgba + y * bytesPerRow
                for x in 0..<width {
                    let i = y * width + x
                    o[i] = (Float(row[x * 4]) - mean[0]) / std[0]
                    o[plane + i] = (Float(row[x * 4 + 1]) - mean[1]) / std[1]
                    o[plane * 2 + i] = (Float(row[x * 4 + 2]) - mean[2]) / std[2]
                }
            }
        }
        return out
    }

    /// 原照片里红方是否在上 (A 行比 J 行更靠下)
    static func redOnTop(_ corners: [Double]) -> Bool {
        (corners[1] + corners[3]) / 2 > (corners[5] + corners[7]) / 2
    }
}

/// 3x3 透视变换 (4 点对求解, 与 Matrix.setPolyToPoly / cv2.getPerspectiveTransform 等价)
struct Homography {
    /// row-major, m[8] = 1
    let m: [Double]

    /// src/dst: [x0,y0, x1,y1, x2,y2, x3,y3]
    init?(from src: [Double], to dst: [Double]) {
        var a = [[Double]](repeating: [Double](repeating: 0, count: 9), count: 8)
        for i in 0..<4 {
            let x = src[i * 2], y = src[i * 2 + 1], u = dst[i * 2], v = dst[i * 2 + 1]
            a[i * 2] = [x, y, 1, 0, 0, 0, -u * x, -u * y, u]
            a[i * 2 + 1] = [0, 0, 0, x, y, 1, -v * x, -v * y, v]
        }
        // 高斯消元 (部分主元)
        for col in 0..<8 {
            var pivot = col
            for r in col + 1..<8 where abs(a[r][col]) > abs(a[pivot][col]) { pivot = r }
            guard abs(a[pivot][col]) > 1e-12 else { return nil }
            a.swapAt(col, pivot)
            for r in 0..<8 where r != col {
                let f = a[r][col] / a[col][col]
                if f == 0 { continue }
                for c in col..<9 { a[r][c] -= f * a[col][c] }
            }
        }
        m = (0..<8).map { a[$0][8] / a[$0][$0] } + [1]
    }

    init(matrix: [Double]) { m = matrix }

    func apply(_ x: Double, _ y: Double) -> (Double, Double) {
        let w = m[6] * x + m[7] * y + m[8]
        return ((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    var inverse: Homography? {
        let a = m
        let det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        guard abs(det) > 1e-12 else { return nil }
        let inv = [
            (a[4] * a[8] - a[5] * a[7]) / det, (a[2] * a[7] - a[1] * a[8]) / det, (a[1] * a[5] - a[2] * a[4]) / det,
            (a[5] * a[6] - a[3] * a[8]) / det, (a[0] * a[8] - a[2] * a[6]) / det, (a[2] * a[3] - a[0] * a[5]) / det,
            (a[3] * a[7] - a[4] * a[6]) / det, (a[1] * a[6] - a[0] * a[7]) / det, (a[0] * a[4] - a[1] * a[3]) / det,
        ]
        return Homography(matrix: inv.map { $0 / inv[8] })
    }

    /// 双线性采样做透视校正: 源 RGBA8 -> 目标 RGBA8 (越界填黑, 与 cv2/Canvas 默认一致)
    static func warp(src: UnsafePointer<UInt8>, srcW: Int, srcH: Int, srcStride: Int,
                     dst: UnsafeMutablePointer<UInt8>, dstW: Int, dstH: Int, dstStride: Int, inverse h: Homography) {
        @inline(__always) func px(_ x: Int, _ y: Int, _ ch: Int) -> Double {
            (x < 0 || y < 0 || x >= srcW || y >= srcH) ? 0 : Double(src[y * srcStride + x * 4 + ch])
        }
        for y in 0..<dstH {
            let out = dst + y * dstStride
            for x in 0..<dstW {
                let (sx, sy) = h.apply(Double(x) + 0.5, Double(y) + 0.5)
                let fx = sx - 0.5, fy = sy - 0.5
                let x0 = Int(floor(fx)), y0 = Int(floor(fy))
                let ax = fx - Double(x0), ay = fy - Double(y0)
                for ch in 0..<3 {
                    let top = px(x0, y0, ch) * (1 - ax) + px(x0 + 1, y0, ch) * ax
                    let bottom = px(x0, y0 + 1, ch) * (1 - ax) + px(x0 + 1, y0 + 1, ch) * ax
                    out[x * 4 + ch] = UInt8(min(max((top * (1 - ay) + bottom * ay).rounded(), 0), 255))
                }
                out[x * 4 + 3] = 255
            }
        }
    }
}
