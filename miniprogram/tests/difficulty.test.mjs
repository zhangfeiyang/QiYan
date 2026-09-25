import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const game = require('../core/xiangqi');
const { difficulties, engineProfile } = require('../generated/product-config');
const storage = new Map();
globalThis.wx = {
  getStorageSync: (key) => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: (key) => storage.delete(key),
};
const store = require('../core/store');

assert.equal(difficulties.length, 8);
assert.equal(store.getLastSetup().difficulty, 1, 'new players start with gentle practice');
for (let oldLevel = 1; oldLevel <= 15; oldLevel++) {
  storage.set('lastGameSetup', { difficulty: oldLevel, playerSide: 0 });
  const setup = store.getLastSetup();
  assert.equal(setup.difficulty, Math.ceil(oldLevel / 3));
  assert.equal(setup.playerSide, 0);
  assert.equal(setup.difficultyVersion, 2);
  assert.deepEqual(store.getLastSetup(), setup, 'migration must only happen once');
}
store.saveLastSetup({ difficulty: 4 });
assert.equal(store.getLastSetup().difficulty, 4, 'new tier IDs must not be migrated again');
store.saveLastSetup({ difficulty: 99 });
assert.equal(store.getLastSetup().difficulty, 8);

function seeded(seed) {
  let state = seed;
  return () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 4294967296; };
}
const capturePosition = game.parseFen('4k4/9/9/9/4p4/9/9/r8/9/R3K4 w - - 0 1').board;
const before = game.toFen(capturePosition, game.RED);
const captures = [];
for (const level of [1, 2]) {
  let count = 0;
  const random = seeded(72);
  const legal = game.legalMoves(capturePosition, game.RED);
  for (let i = 0; i < 400; i++) {
    const move = game.localBestMove(capturePosition, game.RED, level, random);
    assert.ok(legal.some((candidate) => game.moveToUci(candidate) === game.moveToUci(move)));
    if (move.captured) count++;
  }
  captures.push(count);
}
assert.ok(captures[0] < 140, `gentle practice should miss most free captures (${captures[0]})`);
assert.ok(captures[1] > 260, `entry should notice more simple opportunities (${captures[1]})`);
assert.equal(game.toFen(capturePosition, game.RED), before, 'analysis must not mutate caller board');

for (const level of [1, 2]) {
  const position = game.parseFen('3k5/9/9/9/9/9/9/4r4/9/R3K4 w - - 0 1');
  const random = seeded(level);
  for (let i = 0; i < 50; i++) {
    const move = game.localBestMove(position.board, position.side, level, random);
    assert.equal(game.isInCheck(game.applyMove(position.board, move), position.side), false,
      'even a deliberate mistake must answer check legally');
  }
}
const beforeProfile = store.getProfile();
store.recordResult(1, 350, false);
const practiceProfile = store.getProfile();
assert.equal(practiceProfile.rating, beforeProfile.rating, 'practice should not change Elo');
assert.equal(practiceProfile.rd, beforeProfile.rd);
assert.deepEqual(practiceProfile, beforeProfile, 'practice must not change ranked wins, losses or total games');
assert.equal(engineProfile.difficultyVersion, 2);
console.log(`difficulty tests passed (capture rates ${captures.join('/')} of 400)`);
