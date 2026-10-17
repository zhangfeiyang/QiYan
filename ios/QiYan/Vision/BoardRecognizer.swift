import CoreGraphics
import Foundation
import ImageIO
import UIKit

/// 一次识别的结果 (与安卓 RecognizedBoard 同义)
struct RecognizedBoard {
    /// 10x9, row 0 = 黑方底线
    var layout: [[Character]]
    var confidence: [[Float]]
    /// 原图坐标 [A0, A8, J0, J8]
    let corners: [Double]
    let cornerScores: [Float]

    var redOnTop: Bool { VisionMath.redOnTop(corners) }
    var offBoardCount: Int { layout.reduce(0) { $0 + $1.filter { $0 == BoardLayout.offBoard }.count } }
    var meanCornerScore: Float { cornerScores.isEmpty ? 0 : cornerScores.reduce(0, +) / Float(cornerScores.count) }
}

/// 照片识局: RTMPose 找四角 -> 透视校正 -> SwinV2 一次分出 90 个点位 (模型 MIT, 见关于).
/// 推理很重 (0.5~2 s), 只在后台线程调用.
final class BoardRecognizer: @unchecked Sendable {
    private let handle: OpaquePointer
    private let lock = NSLock()
    static let maxImageSide: CGFloat = 1920

    private init(handle: OpaquePointer) { self.handle = handle }
    deinit { qy_vision_free(handle) }

    static func create() -> BoardRecognizer? {
        guard let pose = Bundle.main.path(forResource: "board_pose", ofType: "onnx"),
              let layout = Bundle.main.path(forResource: "board_layout", ofType: "onnx") else { return nil }
        var err = [CChar](repeating: 0, count: 256)
        guard let h = qy_vision_create(pose, layout, &err, 255) else {
            NSLog("BoardRecognizer: %@", String(cString: err))
            return nil
        }
        return BoardRecognizer(handle: h)
    }

    /// 读图 (按 EXIF 方向摆正), 长边压到 1920
    static func loadImage(_ data: Data) -> CGImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                     kCGImageSourceCreateThumbnailWithTransform: true,
                                     kCGImageSourceThumbnailMaxPixelSize: maxImageSide]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }

    static func loadImage(_ image: UIImage) -> CGImage? {
        let size = image.size
        let scale = min(1, maxImageSide / max(size.width, size.height))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }.cgImage
    }

    func recognize(_ image: CGImage) -> RecognizedBoard? {
        lock.lock(); defer { lock.unlock() }
        guard let src = RGBA(image) else { return nil }
        let frame = VisionMath.PoseFrame(width: Double(src.width), height: Double(src.height))
        // 1. 四角: 原图缩放进 256x256, 四周填黑
        let size = VisionMath.poseSize
        guard let poseIn = RGBA(width: size, height: size, draw: { ctx in
            ctx.setFillColor(UIColor.black.cgColor)
            ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
            let (x0, y0) = frame.toInput(0, 0)
            // CG 坐标原点在左下: 把图像上下翻到位图的行序
            let w = Double(src.width) * frame.k, h = Double(src.height) * frame.k
            ctx.draw(image, in: CGRect(x: x0, y: Double(size) - y0 - h, width: w, height: h))
        }) else { return nil }
        let poseInput = poseIn.tensor()
        var sx = [Float](repeating: 0, count: 4 * 512), sy = [Float](repeating: 0, count: 4 * 512)
        guard qy_vision_pose(handle, poseInput, &sx, &sy) == 0 else { return nil }
        let (corners, scores) = VisionMath.decodeCorners(simccX: sx, simccY: sy, frame: frame)
        return classify(src, corners: corners, scores: scores)
    }

    /// 用给定四角重新识别 (跳过关键点模型)
    func recognize(_ image: CGImage, corners: [Double]) -> RecognizedBoard? {
        lock.lock(); defer { lock.unlock() }
        guard let src = RGBA(image) else { return nil }
        return classify(src, corners: corners, scores: [1, 1, 1, 1])
    }

    private func classify(_ src: RGBA, corners: [Double], scores: [Float]) -> RecognizedBoard? {
        // 2. 透视校正到 400x450 (四角落在留白 25 处), 再缩到 280x315 分类
        guard let h = Homography(from: corners, to: VisionMath.warpTargets)?.inverse else { return nil }
        let warped = RGBA(width: VisionMath.warpW, height: VisionMath.warpH)
        src.bytes.withUnsafeBufferPointer { s in
            warped.bytes.withUnsafeMutableBufferPointer { d in
                Homography.warp(src: s.baseAddress!, srcW: src.width, srcH: src.height, srcStride: src.width * 4,
                                dst: d.baseAddress!, dstW: warped.width, dstH: warped.height, dstStride: warped.width * 4,
                                inverse: h)
            }
        }
        guard let warpedImage = warped.cgImage(),
              let clsIn = RGBA(width: VisionMath.clsW, height: VisionMath.clsH, draw: { ctx in
                  ctx.interpolationQuality = .medium
                  ctx.draw(warpedImage, in: CGRect(x: 0, y: 0, width: VisionMath.clsW, height: VisionMath.clsH))
              }) else { return nil }
        let input = clsIn.tensor()
        var probs = [Float](repeating: 0, count: 90 * 16)
        guard qy_vision_layout(handle, input, Int32(VisionMath.clsW), Int32(VisionMath.clsH), &probs) == 0 else { return nil }
        let (layout, conf) = VisionMath.decodeLayout(probs)
        return RecognizedBoard(layout: layout, confidence: conf, corners: corners, cornerScores: scores)
    }
}

/// RGBA8 位图 (行序自上而下)
private final class RGBA {
    let width: Int, height: Int
    var bytes: [UInt8]

    init(width: Int, height: Int) {
        self.width = width
        self.height = height
        bytes = [UInt8](repeating: 0, count: width * height * 4)
    }

    convenience init?(_ image: CGImage) {
        self.init(width: image.width, height: image.height, draw: { ctx in
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        })
    }

    convenience init?(width: Int, height: Int, draw: (CGContext) -> Void) {
        self.init(width: width, height: height)
        let ok: Bool = bytes.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            draw(ctx)
            return true
        }
        if !ok { return nil }
    }

    func tensor() -> [Float] {
        bytes.withUnsafeBufferPointer { VisionMath.normalize(rgba: $0.baseAddress!, width: width, height: height, bytesPerRow: width * 4) }
    }

    func cgImage() -> CGImage? {
        let data = Data(bytes) as CFData
        guard let provider = CGDataProvider(data: data) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}
