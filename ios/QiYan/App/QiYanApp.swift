import SwiftUI

@main
struct QiYanApp: App {
    @StateObject private var router = Router()
    @ObservedObject private var settings = AppSettings.shared
    @Environment(\.scenePhase) private var phase

    init() {
        UINavigationBar.appearance().tintColor = UIColor.token("action")
    }

    var body: some Scene {
        WindowGroup {
            NavigationStack(path: $router.path) {
                HomeView()
                    .navigationDestination(for: Route.self) { route in
                        route.view.toolbar(.hidden, for: .navigationBar)
                    }
            }
            .environmentObject(router)
            .preferredColorScheme(settings.colorScheme)
            .tint(Theme.action)
            .onChange(of: phase) { _, p in
                if p != .active { Bgm.shared.pause() }
            }
            .task {
                // 截图/UI 测试: -openRoute game|newGame|settings|... 直接打开某一页
                if let name = UserDefaults.standard.string(forKey: "openRoute"), let r = Route.named(name) { router.go(r) }
                #if DEBUG
                if UserDefaults.standard.string(forKey: "openRoute") == "replayDemo" { router.go(.replay(DemoData.seedReplay())) }
                #endif
            }
        }
    }
}

/// 残局入口: 每日一题 / 残局研习 / 千题库 / 历史战役 / 杀法宝典
enum PuzzleLaunch: Hashable {
    case daily
    case puzzle(Int)
    case catalog(Int)
    case battle(Int)
}

enum Route: Hashable {
    case game(GameConfig)
    case newGame
    case settings
    case rankHall
    case replays
    case replay(Int64)
    case tactics
    case rush
    case catalog
    case puzzles
    case puzzle(PuzzleLaunch)
    case history
    case campaign
    case live
    case recognize
    case licenses

    static func named(_ name: String) -> Route? {
        let d = UserDefaults.standard
        switch name {
        case "game":
            return .game(GameConfig(difficulty: max(d.integer(forKey: "openLevel"), 1), playerSide: Side.red,
                                    startFen: d.string(forKey: "openFen")))
        case "newGame": return .newGame
        case "settings": return .settings
        case "rankHall": return .rankHall
        case "replays": return .replays
        case "tactics": return .tactics
        case "rush": return .rush
        case "catalog": return .catalog
        case "puzzles": return .puzzles
        case "daily": return .puzzle(.daily)
        case "puzzle": return .puzzle(.puzzle(max(d.integer(forKey: "openPuzzle"), 1)))
        case "history": return .history
        case "campaign": return .campaign
        case "live": return .live
        case "recognize": return .recognize
        case "licenses": return .licenses
        default: return nil
        }
    }

    @MainActor @ViewBuilder var view: some View {
        switch self {
        case .game(let config): GameView(config: config)
        case .newGame: NewGameView()
        case .settings: SettingsView()
        case .rankHall: RankHallView()
        case .replays: ReplayListView()
        case .replay(let id): ReplayViewerView(gameId: id)
        case .tactics: TacticsAcademyView()
        case .rush: PuzzleRushView()
        case .catalog: PuzzleCatalogView()
        case .puzzles: PuzzleListView()
        case .puzzle(let launch): PuzzleView(launch: launch)
        case .history: HistoryView()
        case .campaign: CampaignView()
        case .live: LiveAdviceView()
        case .recognize: RecognizeView()
        case .licenses: LicensesView()
        }
    }
}

@MainActor
final class Router: ObservableObject {
    @Published var path = NavigationPath()

    func go(_ r: Route) { path.append(r) }

    /// 换掉当前页 (安卓里 startActivity + finish)
    func replace(_ r: Route) {
        if !path.isEmpty { path.removeLast() }
        path.append(r)
    }

    func popToRoot() { path = NavigationPath() }
}

#if DEBUG
/// 商店截图用的演示数据 (只在调试版里): 一盘 PikaFish 自对弈的棋谱
enum DemoData {
    static let moves = "c3c4 b7c7 h2e2 c9e7 h0g2 c6c5 i0h0 c5c4 c0a2 i9i8 e2e6 d9e8 e3e4 i8f8 g2e3 c4c3 e4e5 f8f3 b0d1 b9d8 e6i6 h9i7 f0e1 f3g3 e5e6 c3c2 b2b3 g3g5 e6e7 g9e7 e3c2 g5i5 b3b6 d8c6 i6c6 i7h5 h0i0 h5f4"
        .split(separator: " ").map(String.init)

    static func seedReplay() -> Int64 {
        if let r = GameStore.shared.records.first(where: { $0.moves == moves }) { return r.id }
        let start = Date().addingTimeInterval(-3600)
        return GameStore.shared.save(GameRecord(id: 0, startTime: start, endTime: start.addingTimeInterval(1500), playerSide: Side.red,
                                                difficulty: 5, engineElo: DifficultySettings.nominalElo(5), result: 1, ratingDelta: 18,
                                                playerRating: 1620, moves: moves, initialFen: Position.startFen, endReason: L("status_resign")))
    }
}
#endif

