// swift-tools-version:5.9
// 只为在 Mac 上 `swift test` 跑核心规则测试; 应用工程直接以源文件引用 Core/.
import PackageDescription

let package = Package(
    name: "QiYanCore",
    platforms: [.macOS(.v13), .iOS(.v17)],
    targets: [
        .target(name: "QiYanCore", path: "Core"),
        .testTarget(name: "QiYanCoreTests", dependencies: ["QiYanCore"], path: "CoreTests",
                    resources: [.copy("Fixtures")]),
    ]
)
