import Foundation

/// Glicko-2 评分 (与安卓 RatingSystem.kt 同算法)
enum Glicko2 {
    private static let scale = 173.7178
    private static let tau = 0.5

    struct Rating: Equatable {
        var rating = 1500.0
        var rd = 350.0
        var sigma = 0.06
        var displayedRating: Int { max(Int(rating), 0) }
    }

    struct Match {
        let opponentRating: Double
        let opponentRd: Double
        /// 1 胜, 0.5 和, 0 负
        let score: Double
    }

    static func update(_ player: inout Rating, _ matches: [Match], systemConstant: Double = tau) {
        if matches.isEmpty {
            player.rd = sqrt(player.rd * player.rd + player.sigma * player.sigma)
            return
        }
        let mu = (player.rating - 1500) / scale
        let phi = player.rd / scale
        var vInv = 0.0, sumG = 0.0
        for m in matches {
            let muJ = (m.opponentRating - 1500) / scale
            let phiJ = m.opponentRd / scale
            let gj = g(phiJ)
            let e = expected(mu, muJ, phiJ)
            vInv += gj * gj * e * (1 - e)
            sumG += gj * (m.score - e)
        }
        guard vInv > 0 else { return }
        let v = 1 / vInv
        let delta = v * sumG
        let deltaSq = delta * delta
        let a = log(player.sigma * player.sigma)
        let phiSq = phi * phi

        var aLow = a
        var aHigh: Double
        if deltaSq > phiSq + v {
            aHigh = log(deltaSq - phiSq - v)
        } else {
            var k = 1.0
            while f(a - k * systemConstant, deltaSq, v, phi, systemConstant, a) < 0 && k < 100 { k += 1 }
            aHigh = a
            aLow = a - k * systemConstant
        }
        if aLow > aHigh { swap(&aLow, &aHigh) }
        var fLow = f(aLow, deltaSq, v, phi, systemConstant, a)
        var aNew = (aLow + aHigh) / 2
        var iter = 0
        while iter < 100 && abs(aHigh - aLow) > 1e-6 {
            aNew = (aLow + aHigh) / 2
            let fNew = f(aNew, deltaSq, v, phi, systemConstant, a)
            if fNew * fLow <= 0 { aHigh = aNew } else { aLow = aNew; fLow = fNew }
            iter += 1
        }
        let newSigma = exp(aNew / 2)
        let phiStar = sqrt(phiSq + newSigma * newSigma)
        let newPhi = 1 / sqrt(1 / (phiStar * phiStar) + 1 / v)
        let newMu = mu + newPhi * newPhi * sumG

        player.rating = max(newMu * scale + 1500, 0)
        player.rd = min(max(newPhi * scale, 30), 350)
        player.sigma = min(max(newSigma, 0.01), 0.5)
    }

    private static func g(_ phi: Double) -> Double { 1 / sqrt(1 + 3 * phi * phi / (Double.pi * Double.pi)) }

    private static func expected(_ mu: Double, _ muJ: Double, _ phiJ: Double) -> Double {
        1 / (1 + exp(-g(phiJ) * (mu - muJ)))
    }

    private static func f(_ x: Double, _ deltaSq: Double, _ v: Double, _ phi: Double, _ tau: Double, _ a: Double) -> Double {
        let ex = exp(x)
        let d = phi * phi + v + ex
        return ex * (deltaSq - phi * phi - v - ex) / (2 * d * d) - (x - a) / (tau * tau)
    }
}

enum GameResult: Int {
    case win = 1, draw = 0, loss = -1
    var score: Double { [1.0, 0.5, 0.0][self == .win ? 0 : (self == .draw ? 1 : 2)] }
}

/// 段位: 十枚印. 颜色键对应资产里的 rank_* 色.
struct UserRank: Equatable {
    let id: Int
    let minRating: Int
    let maxRating: Int
    let colorKey: String
    var nameKey: String { "rank_\(id)" }

    static let ranks: [UserRank] = [
        UserRank(id: 1, minRating: 0, maxRating: 999, colorKey: "rank_bronze"),
        UserRank(id: 2, minRating: 1000, maxRating: 1199, colorKey: "rank_bronze"),
        UserRank(id: 3, minRating: 1200, maxRating: 1399, colorKey: "rank_silver"),
        UserRank(id: 4, minRating: 1400, maxRating: 1599, colorKey: "rank_silver"),
        UserRank(id: 5, minRating: 1600, maxRating: 1799, colorKey: "rank_gold"),
        UserRank(id: 6, minRating: 1800, maxRating: 1999, colorKey: "rank_gold"),
        UserRank(id: 7, minRating: 2000, maxRating: 2199, colorKey: "rank_platinum"),
        UserRank(id: 8, minRating: 2200, maxRating: 2399, colorKey: "rank_diamond"),
        UserRank(id: 9, minRating: 2400, maxRating: 2599, colorKey: "rank_master"),
        UserRank(id: 10, minRating: 2600, maxRating: 9999, colorKey: "rank_grandmaster"),
    ]

    static func fromRating(_ rating: Int) -> UserRank {
        ranks.first { rating >= $0.minRating && rating <= $0.maxRating } ?? ranks[0]
    }
}

/// 段位阶梯: 前 3 盘计分局定级只定分不授印, 下满后揭晓首枚印.
enum RankLadder {
    static let placementGames = 3

    static func inPlacement(_ ratedGames: Int) -> Bool { ratedGames < placementGames }

    static func completesPlacement(before: Int, after: Int) -> Bool { inPlacement(before) && !inPlacement(after) }

    static func earnedSeal(ratingBefore: Int, ratingAfter: Int, gamesBefore: Int, gamesAfter: Int) -> UserRank? {
        if completesPlacement(before: gamesBefore, after: gamesAfter) { return UserRank.fromRating(ratingAfter) }
        if inPlacement(gamesAfter) { return nil }
        return promoted(before: ratingBefore, after: ratingAfter)
    }

    static func promoted(before: Int, after: Int) -> UserRank? {
        let from = UserRank.fromRating(before), to = UserRank.fromRating(after)
        return to.id > from.id ? to : nil
    }

    struct Progress {
        let current: UserRank
        let next: UserRank?
        let fraction: Double
        let pointsToNext: Int
    }

    static func progress(_ rating: Int) -> Progress {
        let safe = max(rating, 0)
        let current = UserRank.fromRating(safe)
        guard let next = UserRank.ranks.first(where: { $0.id == current.id + 1 }) else {
            return Progress(current: current, next: nil, fraction: 1, pointsToNext: 0)
        }
        let span = max(next.minRating - current.minRating, 1)
        let fraction = min(max(Double(safe - current.minRating) / Double(span), 0), 1)
        return Progress(current: current, next: next, fraction: fraction, pointsToNext: max(next.minRating - safe, 0))
    }
}

/// 每日一题快照
struct DailyPracticeSnapshot: Equatable {
    let puzzleId: Int
    let completedToday: Bool
    let completedDays: Int
    let currentStreak: Int
    let bestStreak: Int
}

/// 每日一题: 按本地日历日 (epoch day, 自 1970-01-01 起的天数) 轮换.
enum DailyPractice {
    /// 前 7 次只出一两步杀
    static let starterDays = 7
    /// 2026-01-01 的 epoch day
    static let anchorDay = 20454

    static func puzzleId(today: Int, puzzleIds: [Int]) -> Int {
        precondition(!puzzleIds.isEmpty)
        let day = today - anchorDay
        let n = puzzleIds.count
        return puzzleIds[((day % n) + n) % n]
    }

    static func puzzleIdFor(today: Int, puzzleIds: [Int], starterIds: [Int], completed: Set<Int>) -> Int {
        let before = completed.filter { $0 < today }.count
        return before < starterDays && !starterIds.isEmpty
            ? puzzleId(today: today, puzzleIds: starterIds)
            : puzzleId(today: today, puzzleIds: puzzleIds)
    }

    static func recordCompletion(today: Int, challengeDay: Int, solvedPuzzleId: Int, puzzleIds: [Int],
                                 completed: Set<Int>, starterIds: [Int] = []) -> Set<Int> {
        guard challengeDay == today,
              solvedPuzzleId == puzzleIdFor(today: today, puzzleIds: puzzleIds, starterIds: starterIds, completed: completed)
        else { return completed }
        return completed.union([today])
    }

    static func snapshot(today: Int, puzzleIds: [Int], completed: Set<Int>, starterIds: [Int] = []) -> DailyPracticeSnapshot {
        let days = completed.filter { $0 <= today }.sorted()
        var best = 0, run = 0
        var previous: Int?
        for d in days {
            run = previous.map { $0 + 1 == d } == true ? run + 1 : 1
            best = max(best, run)
            previous = d
        }
        let set = Set(days)
        var cursor = set.contains(today) ? today : today - 1
        var current = 0
        while set.contains(cursor) { current += 1; cursor -= 1 }
        return DailyPracticeSnapshot(
            puzzleId: puzzleIdFor(today: today, puzzleIds: puzzleIds, starterIds: starterIds, completed: set),
            completedToday: set.contains(today), completedDays: days.count, currentStreak: current, bestStreak: best)
    }

    /// 本地日历日 -> epoch day
    static func epochDay(_ date: Date, calendar: Calendar = .current) -> Int {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let d = utc.date(from: DateComponents(year: c.year, month: c.month, day: c.day))!
        return Int(d.timeIntervalSince1970 / 86400)
    }

    /// epoch day -> 那一天的本地零点
    static func date(epochDay: Int, calendar: Calendar = .current) -> Date {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let d = Date(timeIntervalSince1970: Double(epochDay) * 86400)
        return calendar.date(from: utc.dateComponents([.year, .month, .day], from: d)) ?? d
    }
}
