import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const game = require('../core/xiangqi.js');
const notation = require('../core/notation.js');

const board = game.cloneBoard(game.INITIAL_BOARD);

// ====== 走法生成 ======

assert.equal(game.legalMoves(board, game.RED).length, 44, '初始局面红方应有44个合法着法');
assert.deepEqual(
  game.legalMoves(board, game.RED, { row: 6, col: 0 }).map((move) => move.to),
  [{ row: 5, col: 0 }]
);
assert.equal(game.legalMoves(board, game.RED, { row: 9, col: 1 }).length, 2);

const move = game.localBestMove(board, game.RED);
assert.ok(move && game.legalMoves(board, game.RED).some((item) => JSON.stringify(item) === JSON.stringify(move)));

// 每个 Lv.1-9 都必须返回合法着法
for (let level = 1; level <= 9; level += 1) {
  const reply = game.localBestMove(board, game.BLACK, level);
  assert.ok(reply, `Lv.${level} 黑方必须有回复`);
  assert.ok(
    game.legalMoves(board, game.BLACK).some((item) => JSON.stringify(item) === JSON.stringify(reply)),
    `Lv.${level} 返回的着法应属于黑方合法着法集`
  );
}

// ====== 白脸将 ======
// 这条规则以前整个漏了 —— 少了它, 小程序会放行"走开一个子把自己的帅亮给
// 对方的将", 而 App 判它非法。规则不一致比慢半拍严重得多。

const flying = [
  ['', '', '', '', 'k', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', 'R', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', 'K', '', '', '', ''],
];
assert.equal(game.isFlyingGeneral(flying), false, '中间有红车挡着, 不算白脸将');
// 红车横移让开中路 → 双将对脸, 这一步必须被判非法
const rookSideways = game.legalMoves(flying, game.RED, { row: 7, col: 4 });
assert.ok(
  rookSideways.every((m) => m.to.col === 4),
  `红车不能走离中线, 实际: ${JSON.stringify(rookSideways.map((m) => m.to))}`
);

const flyingNow = game.applyMove(flying, { from: { row: 7, col: 4 }, to: { row: 7, col: 3 } });
assert.equal(game.isFlyingGeneral(flyingNow), true, '双将同列且中间无子 = 白脸将');
assert.equal(game.isInCheck(flyingNow, game.RED), true, '白脸将等同于被将军');

// ====== 一步杀 ======

const mate1 = [
  ['', '', '', '', 'k', '', '', '', ''],
  ['', '', '', '', 'R', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['R', 'N', 'B', 'A', 'K', 'A', 'B', 'N', 'R'],
];
const mating = game.localBestMove(mate1, game.RED, 9);
assert.ok(mating && mating.to.row === 0 && mating.to.col === 4, `Lv.9 应能 1 步吃将，实际: ${JSON.stringify(mating)}`);

// ====== 评估 ======

assert.equal(game.evaluate(board, game.RED), 0, '镜像初始局面分应为 0');
const adv = game.cloneBoard(board); adv[0][0] = '';
assert.ok(game.evaluate(adv, game.RED) > 0, '黑方丢一车红方分应为正');
const loss = game.cloneBoard(board); loss[9][0] = '';
assert.ok(game.evaluate(loss, game.RED) < 0, '红方丢一车红方分应为负');
assert.ok(game.redWinRate(adv) > 50 && game.redWinRate(loss) < 50, '胜率应与优势同向');

// ====== FEN ======

assert.match(game.toFen(board, game.RED), /^rnbakabnr/);
assert.equal(
  game.toFen(board, game.RED),
  'rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1',
  'toFen 必须输出标准格式 —— 云端引擎按它解析 position fen'
);
const parsed = game.parseFen(game.INITIAL_FEN);
assert.ok(parsed, 'INITIAL_FEN 必须能解析');
assert.deepEqual(parsed.board, game.INITIAL_BOARD, 'FEN 往返必须还原同一个棋盘');
assert.equal(parsed.side, game.RED);
assert.equal(game.parseFen('garbage'), null, '坏 FEN 返回 null, 由调用方退回标准开局');

// ====== UCI ======
// 与 Android 的 UciCoords 同向: 列 a-i 自左向右, 行 0-9 自红方底线向上

assert.equal(game.moveToUci({ from: { row: 7, col: 1 }, to: { row: 7, col: 4 } }), 'b2e2', '炮八平五 = b2e2');
const roundTrip = game.uciToMove('b2e2', board);
assert.deepEqual(roundTrip.from, { row: 7, col: 1 });
assert.deepEqual(roundTrip.to, { row: 7, col: 4 });

// ====== 中文记谱 ======

// 红方汉字数字, 列号自红方右侧(col 8 = 一)起数; 黑方阿拉伯数字, 自黑方右侧(col 0 = 1)起数
assert.equal(notation.toChinese({ from: { row: 7, col: 7 }, to: { row: 7, col: 4 } }, board), '炮二平五');
assert.equal(notation.toChinese({ from: { row: 7, col: 1 }, to: { row: 7, col: 4 } }, board), '炮八平五');
assert.equal(notation.toChinese({ from: { row: 9, col: 7 }, to: { row: 7, col: 6 } }, board), '马二进三');
assert.equal(notation.toChinese({ from: { row: 6, col: 4 }, to: { row: 5, col: 4 } }, board), '兵五进一');
assert.equal(notation.toChinese({ from: { row: 2, col: 1 }, to: { row: 2, col: 4 } }, board), '炮2平5');
assert.equal(notation.toChinese({ from: { row: 0, col: 1 }, to: { row: 2, col: 2 } }, board), '马2进3');

// 同列两子用 前/后 前缀替代起始列
const twoRooks = game.cloneBoard(board);
twoRooks[9][0] = '';
twoRooks[8][4] = 'R'; // 中路两个红车: (7? 不, 是 8,4) 与 ...
twoRooks[7][4] = 'R';
assert.equal(notation.toChinese({ from: { row: 7, col: 4 }, to: { row: 6, col: 4 } }, twoRooks).slice(0, 2), '前车');
assert.equal(notation.toChinese({ from: { row: 8, col: 4 }, to: { row: 8, col: 3 } }, twoRooks).slice(0, 2), '后车');

// ====== 终局判定 ======

assert.equal(game.gameOver(board, game.RED).type, game.GAME_OVER.NONE);

// 黑将被红车将死且无处可逃 (九宫三格都被车控住)
const mated = [
  ['', '', '', '', 'k', '', '', '', ''],
  ['', '', '', 'R', '', 'R', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', 'R', '', '', '', '', ''],
  ['', '', '', '', 'K', '', '', '', ''],
];
const over = game.gameOver(mated, game.BLACK);
assert.equal(over.type, game.GAME_OVER.CHECKMATE, `黑方应被将死, 实际: ${over.type}`);
assert.equal(over.winner, game.RED, '走不动的一方输 —— 黑被将死, 赢的是红');

// 自然限着
assert.equal(
  game.gameOver(board, game.RED, [], game.HALFMOVE_DRAW_LIMIT).type,
  game.GAME_OVER.FIFTY_MOVES
);

// 重复局面: 同一个键出现 3 次判和
const repeatKey = game.positionKey(board, game.RED);
const history = new Array(9).fill('other');
history[0] = repeatKey;
history[3] = repeatKey;
history[6] = repeatKey;
assert.equal(game.gameOver(board, game.RED, history, 0).type, game.GAME_OVER.REPETITION);

console.log('xiangqi core tests passed');
