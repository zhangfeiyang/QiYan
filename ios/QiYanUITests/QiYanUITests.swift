import XCTest

/// Drives the app on the simulator and writes screenshots to $QY_SHOTS (default /tmp/qy_ui).
/// tools/ui_test.sh runs one test: `ui_test.sh testGameFlow`.
final class QiYanUITests: XCTestCase {
    private var app: XCUIApplication!
    private let out = ProcessInfo.processInfo.environment["QY_SHOTS"] ?? "/tmp/qy_ui"
    private var lang: String { ProcessInfo.processInfo.environment["QY_LANG"] ?? "zh-Hans" }

    override func setUp() {
        continueAfterFailure = true
        try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
    }

    private func launch(_ args: [String] = []) {
        app = XCUIApplication()
        let extra = (ProcessInfo.processInfo.environment["QY_ARGS"] ?? "").split(separator: ",").map(String.init)
        app.launchArguments = args + extra + ["-AppleLanguages", "(\(lang))", "-AppleLocale", lang]
        app.launch()
    }

    private func shot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: URL(fileURLWithPath: "\(out)/\(name).png"))
    }

    private func wait(_ s: TimeInterval) { Thread.sleep(forTimeInterval: s) }

    /// row 0 = 上方 (黑方底线), col 0 = 左
    private func tapSquare(_ row: Int, _ col: Int) {
        let board = app.descendants(matching: .any)["board"]
        XCTAssertTrue(board.waitForExistence(timeout: 10))
        let g = (board.value as? String ?? "").split(separator: ",").compactMap { Double($0) }
        guard g.count == 3 else { XCTFail("board geometry"); return }
        board.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: g[0] + Double(col) * g[2], dy: g[1] + Double(row) * g[2])).tap()
    }

    private func tapButton(_ label: String) {
        let b = app.buttons[label].firstMatch
        XCTAssertTrue(b.waitForExistence(timeout: 10), label)
        b.tap()
    }

    func testGameFlow() {
        launch(["-openRoute", "game", "-openLevel", "3"])
        wait(3)
        shot("game_start")
        tapSquare(7, 7)          // 炮二
        wait(0.6)
        shot("game_select")
        tapSquare(7, 4)          // 平五
        wait(5)
        shot("game_engine_reply")
        tapButton("提示")
        wait(4)
        shot("game_hint")
        tapButton("认输")
        wait(1)
        shot("game_resign_confirm")
        app.alerts.buttons["确定"].tap()
        wait(3)
        shot("game_result")
        app.buttons.matching(NSPredicate(format: "label CONTAINS '复盘'")).firstMatch.tap()
        wait(2)
        shot("game_review")
    }

    func testCaptureFx() {
        // 中炮打中兵
        launch(["-openRoute", "game", "-openLevel", "2", "-openFen",
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C2C4/9/RNBAKABNR w - - 0 1"])
        wait(3)
        tapSquare(7, 4)
        wait(0.4)
        tapSquare(3, 4)
        wait(0.25)
        shot("fx_1")
        wait(0.35)
        shot("fx_2")
        wait(4)
        shot("fx_3")
    }

    /// 截指定页面: QY_ROUTES=settings,rankHall (每页再往下滚 QY_SCROLLS 屏各截一张)
    func testRoutes() {
        let env = ProcessInfo.processInfo.environment
        let routes = (env["QY_ROUTES"] ?? "settings").split(separator: ",").map(String.init)
        let scrolls = Int(env["QY_SCROLLS"] ?? "0") ?? 0
        for route in routes {
            launch(["-openRoute", route])
            wait(Double(env["QY_WAIT"] ?? "2.5") ?? 2.5)
            shot("\(route)_0")
            for i in 0..<scrolls {
                app.swipeUp(velocity: .slow)
                wait(0.8)
                shot("\(route)_\(i + 1)")
            }
        }
    }

    func testReplay() {
        launch(["-openRoute", "replays"])
        wait(2)
        shot("replays")
        let first = app.buttons.matching(NSPredicate(format: "label CONTAINS '手'")).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 5))
        first.tap()
        wait(2)
        shot("replay_0")
        tapButton("下一步")
        wait(0.8)
        shot("replay_1")
        tapButton("最后一步")
        wait(0.8)
        shot("replay_last")
        tapButton("第一步")
        wait(0.5)
        app.buttons.matching(NSPredicate(format: "label CONTAINS '试下'")).firstMatch.tap()
        wait(0.5)
        tapSquare(9, 1)
        wait(0.3)
        tapSquare(7, 2)
        wait(1)
        shot("replay_variation")
    }

    func testPuzzleSolve() {
        launch(["-openRoute", "puzzle", "-openPuzzle", "2"])
        wait(2.5)
        tapSquare(5, 2)
        wait(0.4)
        tapSquare(5, 4)
        wait(2.5)
        shot("puzzle_solved")
        app.alerts.buttons.element(boundBy: 0).tap()
        wait(1.5)
        shot("puzzle_next")
    }

    func testPuzzleDefense() {
        launch(["-openRoute", "puzzle", "-openPuzzle", "10"])
        wait(3)
        tapSquare(6, 0)
        wait(0.4)
        tapSquare(5, 0)
        wait(3.5)
        shot("puzzle_defense")
    }

    /// QY_IMAGE=/path/to/photo: 识别一张图, 截图 (结果另写到 /tmp/qy_vision_out.txt)
    func testRecognize() {
        let env = ProcessInfo.processInfo.environment
        let image = env["QY_IMAGE"] ?? ""
        launch(["-openRoute", "recognize", "-recognizeFile", image])
        wait(Double(env["QY_WAIT"] ?? "12") ?? 12)
        shot("recognize_" + (image as NSString).lastPathComponent)
        if env["QY_ADVICE"] != nil {
            app.buttons.matching(NSPredicate(format: "label CONTAINS '支招'")).element(boundBy: 1).tap()
            wait(4)
            shot("recognize_advice")
        }
    }

    /// 首页每个入口都能进、能回
    func testHomeNavigation() {
        launch()
        wait(2.5)
        let entries: [(String, String)] = [
            ("实时支招", "实时支招"), ("拍照识别", "拍照识别棋局"), ("每日一题", "每日一题"), ("登峰棋路", "对弈闯关"),
            ("段位", "段位印鉴"), ("历史战役", "历史战役"), ("残局研习", "残局研习"), ("千题", "千题题库"),
            ("残局速拼", "残局速拼"), ("杀法宝典", "杀法宝典"), ("复盘", "复盘"), ("换个对手", "新对局"),
        ]
        var failures: [String] = []
        for (entry, title) in entries {
            let b = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", entry)).firstMatch
            var tries = 0
            while !b.isHittable && tries < 6 { app.swipeUp(velocity: .slow); tries += 1 }
            guard b.exists, b.isHittable else { failures.append("no entry " + entry); shot("nav_missing_" + entry); continue }
            b.tap()
            wait(1.5)
            if !app.staticTexts[title].waitForExistence(timeout: 3) { failures.append("no title " + title); shot("nav_fail_" + entry) }
            let back = app.buttons.matching(NSPredicate(format: "label == '返回' OR label == '取消'")).firstMatch
            if back.exists && back.isHittable { back.tap() }
            wait(1.2)
            for _ in 0..<6 { app.swipeDown(velocity: .fast) }
        }
        XCTAssertEqual(failures, [])
    }

    /// 商店截图 (6.9 寸模拟器跑, 每种语言一套): 首页 / 对局+提示 / 拍照识局+支招 / 复盘 / 残局 / 闯关 / 杀法
    func testStoreShots() {
        let env = ProcessInfo.processInfo.environment
        let tag = lang
        launch()
        wait(3)
        shot("\(tag)_1_home")

        launch(["-openRoute", "game", "-openLevel", "5", "-openFen",
                "rn2kabn1/4ar3/2c1b2c1/p3C1p1p/9/4P4/P1p1N1P1P/BC7/9/RN1AKABR1 w - - 5 9"])
        wait(4)
        app.buttons["ic_hint"].firstMatch.tap()
        wait(4)
        shot("\(tag)_2_game")

        if let image = env["QY_IMAGE"] {
            launch(["-openRoute", "recognize", "-recognizeFile", image])
            wait(12)
            app.buttons["advise"].firstMatch.tap()
            wait(4)
            shot("\(tag)_3_recognize")
        }

        launch(["-openRoute", "replayDemo"])
        wait(3)
        for _ in 0..<22 { app.buttons["ic_next"].firstMatch.tap() }
        wait(1.5)
        shot("\(tag)_4_replay")

        launch(["-openRoute", "puzzles"])
        wait(2.5)
        shot("\(tag)_5_puzzles")

        launch(["-openRoute", "campaign"])
        wait(2.5)
        shot("\(tag)_6_campaign")

        launch(["-openRoute", "tactics"])
        wait(2.5)
        shot("\(tag)_7_tactics")
    }
}

