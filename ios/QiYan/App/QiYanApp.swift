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
