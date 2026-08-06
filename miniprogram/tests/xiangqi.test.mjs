import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require=createRequire(import.meta.url);
const game=require('../core/xiangqi.js');
const board=game.cloneBoard(game.INITIAL_BOARD);
assert.equal(game.legalMoves(board,game.RED).length,44,'初始局面红方应有44个合法着法');
const pawnMoves=game.legalMoves(board,game.RED,{row:6,col:0});
assert.deepEqual(pawnMoves.map((move)=>move.to),[{row:5,col:0}]);
const horseMoves=game.legalMoves(board,game.RED,{row:9,col:1});
assert.equal(horseMoves.length,2);
const move=game.localBestMove(board,game.RED);
assert.ok(move&&game.legalMoves(board,game.RED).some((item)=>JSON.stringify(item)===JSON.stringify(move)));
assert.match(game.toFen(board,game.RED),/^rnbakabnr/);

// 新引擎：每个 Lv.1-9 都必须返回合法着法
for (let level = 1; level <= 9; level += 1) {
  const reply = game.localBestMove(board, game.BLACK, level);
  assert.ok(reply, `Lv.${level} 黑方必须有回复`);
  assert.ok(game.legalMoves(board, game.BLACK).some((item) => JSON.stringify(item) === JSON.stringify(reply)), `Lv.${level} 返回的着法应属于黑方合法着法集`);
}

// 1 步杀：让黑将能被红车一步吃掉。构造：黑将 (0,4)，红车 (1,4)
const mate1 = [
  ['','','','','k','','','',''],
  ['','','','','R','','','',''],
  ['','','','','','','','',''],
  ['','','','','','','','',''],
  ['','','','','','','','',''],
  ['','','','','','','','',''],
  ['','','','','','','','',''],
  ['','','','','','','','',''],
  ['','','','','','','','',''],
  ['R','N','B','A','K','A','B','N','R']
];
const mating = game.localBestMove(mate1, game.RED, 9);
assert.ok(mating && mating.to.row === 0 && mating.to.col === 4, `Lv.9 应能 1 步吃将，实际: ${JSON.stringify(mating)}`);

// evaluate：镜像初始局面分应为 0
assert.equal(game.evaluate(board, game.RED), 0, '镜像初始局面分应为 0');

// 黑方丢一车：红方分应 > 0
const adv = game.cloneBoard(board); adv[0][0] = ''; // 吃掉黑方一车
assert.ok(game.evaluate(adv, game.RED) > 0, '黑方丢一车红方分应为正');

// 红方丢一车：红方分应 < 0
const loss = game.cloneBoard(board); loss[9][0] = ''; // 吃掉红方一车
assert.ok(game.evaluate(loss, game.RED) < 0, '红方丢一车红方分应为负');

console.log('xiangqi core tests passed');
