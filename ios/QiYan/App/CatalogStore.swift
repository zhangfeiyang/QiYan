import Foundation

/// 千题库 (bundle 里的 catalog.txt, 首次用到时解析一次)
enum CatalogStore {
    static let all: [Puzzle] = {
        guard let url = Bundle.main.url(forResource: "catalog", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return PuzzleCatalog.parse(text)
    }()

    static func byId(_ id: Int) -> Puzzle? { all.first { $0.id == id } }

    static func next(_ id: Int) -> Puzzle? {
        guard let i = all.firstIndex(where: { $0.id == id }), i + 1 < all.count else { return nil }
        return all[i + 1]
    }
}
