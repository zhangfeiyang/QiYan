/**
 * 端侧象棋内核: 走法生成 / 规则判定 / 局面评估 / alpha-beta 搜索。
 *
 * 与 Android 端的对应关系:
 *   MoveGenerator.kt  →  pseudoMovesFor / legalMoves
 *   Rules.kt          →  isInCheck / gameOver
 *   Position.kt       →  toFen / parseFen / positionKey
 *
 * 棋盘用 10 行 × 9 列的字符二维数组, row 0 是黑方底线, row 9 是红方底线,
 * 与 Android 的 Square 编号 (row = sq / 9) 同向, 两端对着同一个 FEN 时不会
 * 上下颠倒。大写 = 红方, 小写 = 黑方。
 */
const { engineProfile, levelNotes } = require('../generated/product-config');

const RED = 1;
const BLACK = 0;
const EMPTY = '';
const ROWS = 10;
const COLS = 9;

const INITIAL_BOARD = [
  ['r', 'n', 'b', 'a', 'k', 'a', 'b', 'n', 'r'],
  ['', '', '', '', '', '', '', '', ''],
  ['', 'c', '', '', '', '', '', 'c', ''],
  ['p', '', 'p', '', 'p', '', 'p', '', 'p'],
  ['', '', '', '', '', '', '', '', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['P', '', 'P', '', 'P', '', 'P', '', 'P'],
  ['', 'C', '', '', '', '', '', 'C', ''],
  ['', '', '', '', '', '', '', '', ''],
  ['R', 'N', 'B', 'A', 'K', 'A', 'B', 'N', 'R'],
];

const INITIAL_FEN = 'rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1';

/** 棋子面上的字, 与 Android Piece.redChar / blackChar 一致 */
const CHARS = {
  K: '帅', A: '仕', B: '相', N: '马', R: '车', C: '炮', P: '兵',
  k: '将', a: '士', b: '象', n: '马', r: '车', c: '炮', p: '卒',
};

const VALUE = engineProfile.pieceValues;
const MATE = 100000;

/** 自然限着: 连续 120 个半回合(60 回合)无吃子判和, 与 Position.isFiftyMoveDraw 一致 */
const HALFMOVE_DRAW_LIMIT = 120;

// ====== 基础工具 ======

const cloneBoard = (board) => board.map((row) => row.slice());

const colorOf = (piece) => (!piece ? -1 : piece === piece.toUpperCase() ? RED : BLACK);

const otherSide = (side) => (side === RED ? BLACK : RED);

const inBoard = (row, col) => row >= 0 && row < ROWS && col >= 0 && col < COLS;

const inPalace = (row, col, color) =>
  col >= 3 && col <= 5 && (color === RED ? row >= 7 && row <= 9 : row >= 0 && row <= 2);

const crossedRiver = (row, color) => (color === RED ? row <= 4 : row >= 5);

// ====== 走法生成 ======

function pushIfOpen(board, moves, from, row, col, color, capturesOnly = false) {
  if (!inBoard(row, col)) return;
  const target = board[row][col];
  if (colorOf(target) === color) return;
  if (!capturesOnly || target) {
    moves.push({ from, to: { row, col }, captured: target || EMPTY });
  }
}

const KING_STEPS = [[-1, 0], [1, 0], [0, -1], [0, 1]];
const ADVISOR_STEPS = [[-1, -1], [-1, 1], [1, -1], [1, 1]];
const ELEPHANT_STEPS = [[-2, -2], [-2, 2], [2, -2], [2, 2]];
/** 马的八个方向, 后两位是"马腿"相对起点的偏移 */
const HORSE_STEPS = [
  [-2, -1, -1, 0], [-2, 1, -1, 0], [2, -1, 1, 0], [2, 1, 1, 0],
  [-1, -2, 0, -1], [1, -2, 0, -1], [-1, 2, 0, 1], [1, 2, 0, 1],
];

/**
 * 生成某个格子上棋子的伪合法着法(不检查是否送将)。
 *
 * @param capturesOnly 只要吃子着法。静态搜索和"对方能不能吃到我的将"都只关心这一半。
 */
function pseudoMovesFor(board, row, col, capturesOnly = false) {
  const piece = board[row][col];
  if (!piece) return [];

  const color = colorOf(piece);
  const type = piece.toUpperCase();
  const from = { row, col };
  const moves = [];

  if (type === 'K') {
    for (const [dr, dc] of KING_STEPS) {
      if (inPalace(row + dr, col + dc, color)) {
        pushIfOpen(board, moves, from, row + dr, col + dc, color, capturesOnly);
      }
    }
  } else if (type === 'A') {
    for (const [dr, dc] of ADVISOR_STEPS) {
      if (inPalace(row + dr, col + dc, color)) {
        pushIfOpen(board, moves, from, row + dr, col + dc, color, capturesOnly);
      }
    }
  } else if (type === 'B') {
    for (const [dr, dc] of ELEPHANT_STEPS) {
      const tr = row + dr;
      const tc = col + dc;
      // 象不过河
      const ownSide = color === RED ? tr >= 5 : tr <= 4;
      // 塞象眼: 田字中心有子就走不了
      if (ownSide && inBoard(tr, tc) && !board[row + dr / 2][col + dc / 2]) {
        pushIfOpen(board, moves, from, tr, tc, color, capturesOnly);
      }
    }
  } else if (type === 'N') {
    for (const [dr, dc, lr, lc] of HORSE_STEPS) {
      // 蹩马腿
      if (board[row + lr] && board[row + lr][col + lc]) continue;
      pushIfOpen(board, moves, from, row + dr, col + dc, color, capturesOnly);
    }
  } else if (type === 'R' || type === 'C') {
    const isRook = type === 'R';
    for (const [dr, dc] of KING_STEPS) {
      let tr = row + dr;
      let tc = col + dc;
      let screened = false; // 炮是否已越过炮架
      while (inBoard(tr, tc)) {
        const target = board[tr][tc];
        if (isRook) {
          if (!target) {
            if (!capturesOnly) moves.push({ from, to: { row: tr, col: tc }, captured: EMPTY });
          } else {
            if (colorOf(target) !== color) {
              moves.push({ from, to: { row: tr, col: tc }, captured: target });
            }
            break;
          }
        } else if (!screened) {
          if (!target) {
            if (!capturesOnly) moves.push({ from, to: { row: tr, col: tc }, captured: EMPTY });
          } else {
            screened = true;
          }
        } else if (target) {
          if (colorOf(target) !== color) {
            moves.push({ from, to: { row: tr, col: tc }, captured: target });
          }
          break;
        }
        tr += dr;
        tc += dc;
      }
    }
  } else if (type === 'P') {
    const forward = color === RED ? -1 : 1;
    pushIfOpen(board, moves, from, row + forward, col, color, capturesOnly);
    if (crossedRiver(row, color)) {
      pushIfOpen(board, moves, from, row, col - 1, color, capturesOnly);
      pushIfOpen(board, moves, from, row, col + 1, color, capturesOnly);
    }
  }

  return moves;
}

function applyMove(board, move) {
  const next = cloneBoard(board);
  next[move.to.row][move.to.col] = next[move.from.row][move.from.col];
  next[move.from.row][move.from.col] = EMPTY;
  return next;
}

function findKing(board, color) {
  const king = color === RED ? 'K' : 'k';
  for (let row = 0; row < ROWS; row += 1) {
    for (let col = 0; col < COLS; col += 1) {
      if (board[row][col] === king) return { row, col };
    }
  }
  return null;
}

/**
 * 白脸将(飞将): 双方将帅同列且中间无子。
 *
 * 这条以前整个漏了 —— 少了它, 小程序会放行"我方走开一个子, 把自己的帅
 * 亮给对方的将"这种着法, 而 App 判它非法。规则不一致比慢半拍严重得多。
 */
function isFlyingGeneral(board) {
  const red = findKing(board, RED);
  const black = findKing(board, BLACK);
  if (!red || !black || red.col !== black.col) return false;
  const from = Math.min(red.row, black.row) + 1;
  const to = Math.max(red.row, black.row);
  for (let row = from; row < to; row += 1) {
    if (board[row][red.col]) return false;
  }
  return true;
}

function isInCheck(board, color) {
  const king = findKing(board, color);
  if (!king) return true;
  if (isFlyingGeneral(board)) return true;

  const enemy = otherSide(color);
  for (let row = 0; row < ROWS; row += 1) {
    for (let col = 0; col < COLS; col += 1) {
      if (colorOf(board[row][col]) !== enemy) continue;
      const attacks = pseudoMovesFor(board, row, col, true);
      if (attacks.some((move) => move.to.row === king.row && move.to.col === king.col)) return true;
    }
  }
  return false;
}

/**
 * 合法着法。
 *
 * @param onlyFrom 只要这个格子上的子的着法(点选一个子时用)
 * @param capturesOnly 只要吃子着法(静态搜索用)
 */
function legalMoves(board, side, onlyFrom, capturesOnly = false) {
  const result = [];
  for (let row = 0; row < ROWS; row += 1) {
    for (let col = 0; col < COLS; col += 1) {
      if (colorOf(board[row][col]) !== side) continue;
      if (onlyFrom && (onlyFrom.row !== row || onlyFrom.col !== col)) continue;
      for (const move of pseudoMovesFor(board, row, col, capturesOnly)) {
        if (!isInCheck(applyMove(board, move), side)) result.push(move);
      }
    }
  }
  return result;
}

// ====== 局面序列化 ======

function toFen(board, side, halfmoveClock = 0, fullmoveNumber = 1) {
  const rows = board.map((row) => {
    let empty = 0;
    let out = '';
    for (const piece of row) {
      if (!piece) {
        empty += 1;
      } else {
        if (empty) {
          out += empty;
          empty = 0;
        }
        out += piece;
      }
    }
    return out + (empty || '');
  });
  return `${rows.join('/')} ${side === RED ? 'w' : 'b'} - - ${halfmoveClock} ${fullmoveNumber}`;
}

/** 解析 FEN, 失败返回 null —— 调用方据此退回标准开局, 与 GameViewModel 同样的兜底 */
function parseFen(fen) {
  if (typeof fen !== 'string') return null;
  const parts = fen.trim().split(/\s+/);
  const ranks = parts[0].split('/');
  if (ranks.length !== ROWS) return null;

  const board = [];
  for (const rank of ranks) {
    const row = [];
    for (const ch of rank) {
      if (ch >= '1' && ch <= '9') {
        for (let i = 0; i < Number(ch); i += 1) row.push(EMPTY);
      } else if (CHARS[ch]) {
        row.push(ch);
      } else {
        return null;
      }
    }
    if (row.length !== COLS) return null;
    board.push(row);
  }
  return {
    board,
    side: parts[1] === 'b' ? BLACK : RED,
    halfmoveClock: Number(parts[4]) || 0,
    fullmoveNumber: Number(parts[5]) || 1,
  };
}

/** 重复局面判定用的键。只取子力布局 + 走子方, 不含步数计数器 */
function positionKey(board, side) {
  return `${board.map((row) => row.map((piece) => piece || '.').join('')).join('')}|${side}`;
}

/** UCI 坐标, 与 Android 的 UciCoords 一致: 列 a-i 自左向右, 行 0-9 自红方底线向上 */
function moveToUci(move) {
  const file = (col) => String.fromCharCode(97 + col);
  const rank = (row) => String(ROWS - 1 - row);
  return `${file(move.from.col)}${rank(move.from.row)}${file(move.to.col)}${rank(move.to.row)}`;
}

function uciToMove(uci, board) {
  if (typeof uci !== 'string' || uci.length < 4) return null;
  const col = (ch) => ch.charCodeAt(0) - 97;
  const row = (ch) => ROWS - 1 - Number(ch);
  const from = { row: row(uci[1]), col: col(uci[0]) };
  const to = { row: row(uci[3]), col: col(uci[2]) };
  if (!inBoard(from.row, from.col) || !inBoard(to.row, to.col)) return null;
  return { from, to, captured: board ? board[to.row][to.col] || EMPTY : EMPTY };
}

// ====== 终局判定 ======

const GAME_OVER = {
  NONE: 'none',
  CHECKMATE: 'checkmate',
  STALEMATE: 'stalemate',
  REPETITION: 'repetition',
  FIFTY_MOVES: 'fifty_moves',
};

/**
 * 终局判定, 对应 Rules.gameOver。
 *
 * 返回 `{ type, winner }`, winner 是获胜方颜色, 和局为 -1。
 *
 * 注意 Android 那边 Rules.gameOver 把两个将死分支写反了 (轮到红走且被将死时
 * 返回 CHECKMATE_BLACK, 于是文案显示"红方将死黑方"、判定获胜方也是红方),
 * 这里按正确语义实现: **走不动的一方输**。
 *
 * @param historyKeys 含当前局面在内的所有历史局面键
 */
function gameOver(board, side, historyKeys = [], halfmoveClock = 0) {
  if (!legalMoves(board, side).length) {
    // 困毙在中国象棋里同样判负, 与 Android 的 Rules 一致地单列出来只是为了
    // 文案能说清"被将死"还是"无棋可走"。
    return {
      type: isInCheck(board, side) ? GAME_OVER.CHECKMATE : GAME_OVER.STALEMATE,
      winner: otherSide(side),
    };
  }
  if (historyKeys.length >= 9) {
    const current = positionKey(board, side);
    let count = 0;
    for (const key of historyKeys) if (key === current) count += 1;
    if (count >= 3) return { type: GAME_OVER.REPETITION, winner: -1 };
  }
  if (halfmoveClock >= HALFMOVE_DRAW_LIMIT) {
    return { type: GAME_OVER.FIFTY_MOVES, winner: -1 };
  }
  return { type: GAME_OVER.NONE, winner: -1 };
}

// ====== 评估 ======

/**
 * 位置价值表。
 *
 * `r` 是把棋子折算到"自己视角"的行号 (0 = 对方底线, 9 = 自己底线),
 * 红黑共用同一张表。
 */
function pstBonus(piece, row, col) {
  const type = piece.toUpperCase();
  const red = piece === piece.toUpperCase();
  const r = red ? row : 9 - row;
  switch (type) {
    case 'K':
      return r >= 7 ? (col === 4 ? 6 : 0) : -20;
    case 'A':
      return r >= 7 ? 0 : -20;
    case 'B': {
      const corner = (col === 0 || col === 8) && (r === 0 || r === 4 || r === 5 || r === 9);
      return corner ? 4 : 0;
    }
    case 'N': {
      const center = Math.max(0, 3 - Math.abs(col - 4));
      const active = r < 5;
      return center * (active ? 8 : 4);
    }
    case 'R':
      return r === 4 || r === 5 ? 12 : 0;
    case 'C':
      return r === 1 || r === 2 || r === 7 || r === 8 ? 6 : 0;
    case 'P': {
      if (r >= 5) return 0;
      if (r === 4) return 30;
      if (r === 3) return 50;
      if (r === 2) return 60;
      return 75;
    }
    default:
      return 0;
  }
}

function evaluate(board, side) {
  let score = 0;
  for (let r = 0; r < ROWS; r += 1) {
    for (let c = 0; c < COLS; c += 1) {
      const piece = board[r][c];
      if (!piece) continue;
      const v = VALUE[piece.toUpperCase()] + pstBonus(piece, r, c);
      score += piece === piece.toUpperCase() ? v : -v;
    }
  }
  return side === RED ? score : -score;
}

/**
 * 局面评估折算成红方胜率 (0..100)。
 *
 * 端侧没有 Pikafish 的内部单位, 这里的分数已经是"厘兵 × 1"的量纲, 所以
 * 不做 WinRateAnalyzer 里那步 INTERNAL_TO_CP 换算, 温度系数保持一致。
 */
function redWinRate(board) {
  const cp = evaluate(board, RED);
  return Math.tanh(cp / (400 * 1.8)) * 50 + 50;
}

// ====== 搜索 ======

function mvvLva(move, board) {
  const victim = move.captured;
  if (!victim) return 0;
  const attacker = board[move.from.row][move.from.col];
  return VALUE[victim.toUpperCase()] * 10 - VALUE[attacker.toUpperCase()];
}

function makeKey(move) {
  return `${move.from.row},${move.from.col}->${move.to.row},${move.to.col}`;
}

function orderMoves(moves, board, killers, history) {
  return moves.slice().sort((a, b) => {
    const ca = mvvLva(a, board);
    const cb = mvvLva(b, board);
    if (ca !== cb) return cb - ca;
    const ka = killers ? killers.indexOf(makeKey(a)) : -1;
    const kb = killers ? killers.indexOf(makeKey(b)) : -1;
    if (ka !== kb) return ka - kb;
    const ha = (history && history[makeKey(a)]) || 0;
    const hb = (history && history[makeKey(b)]) || 0;
    return hb - ha;
  });
}

function quiesce(board, side, alpha, beta, deadline) {
  if (Date.now() > deadline) return { score: 0, aborted: true };
  const stand = evaluate(board, side);
  if (stand >= beta) return { score: beta, aborted: false };
  if (stand > alpha) alpha = stand;

  const moves = legalMoves(board, side, undefined, true)
    .sort((a, b) => mvvLva(b, board) - mvvLva(a, board));
  for (const move of moves) {
    const child = quiesce(applyMove(board, move), otherSide(side), -beta, -alpha, deadline);
    if (child.aborted) return child;
    const score = -child.score;
    if (score >= beta) return { score: beta, aborted: false };
    if (score > alpha) alpha = score;
  }
  return { score: alpha, aborted: false };
}

function search(board, side, depth, alpha, beta, deadline, ordering, ply) {
  if (Date.now() > deadline) return { score: 0, aborted: true };

  const inCheck = isInCheck(board, side);
  if (inCheck) depth += 1; // 被将时延伸一层, 免得把杀棋切在地平线外
  if (depth <= 0) {
    if (!ordering.quiescence) return { score: evaluate(board, side), aborted: false };
    return quiesce(board, side, alpha, beta, deadline);
  }

  const moves = legalMoves(board, side);
  if (!moves.length) {
    // 距离根越近的杀分越高, 引擎才会挑最快的那条杀
    return { score: inCheck ? -MATE + ply : 0, aborted: false };
  }

  const killers = [];
  const ordered = orderMoves(moves, board, killers);
  let best = -MATE;
  for (const move of ordered) {
    const child = search(
      applyMove(board, move), otherSide(side), depth - 1, -beta, -alpha, deadline, ordering, ply + 1
    );
    if (child.aborted) return child;
    const score = -child.score;
    if (score > best) best = score;
    if (score > alpha) alpha = score;
    if (alpha >= beta) {
      if (!move.captured) killers.push(makeKey(move));
      break;
    }
  }
  return { score: best, aborted: false };
}

function searchRoot(board, side, depth, ordering, deadline) {
  const moves = legalMoves(board, side);
  if (!moves.length) return { move: null, aborted: false };

  const ordered = orderMoves(moves, board, []);
  let bestMove = ordered[0];
  let bestScore = -MATE;
  let alpha = -MATE;
  for (const move of ordered) {
    const child = search(
      applyMove(board, move), otherSide(side), depth - 1, -MATE, -alpha, deadline, ordering, 0
    );
    if (child.aborted) return { move: bestMove, aborted: true };
    const score = -child.score;
    if (score > bestScore) {
      bestScore = score;
      bestMove = move;
    }
    if (score > alpha) alpha = score;
  }
  return { move: bestMove, aborted: false };
}

function pickRandom(moves) {
  return moves[Math.floor(Math.random() * moves.length)];
}

/**
 * 端侧最佳着法。
 *
 * 档位参数全部来自 shared/config/engine-profile.json —— 深度、是否静搜、
 * 排序策略、时间预算都由那份配置说了算, 改棋力只改配置。
 */
function localBestMove(board, side, level = 5) {
  const config = engineProfile.miniprogramSearchDepths[String(level)];
  if (!config) return null;

  const budget = engineProfile.searchTimeBudgetMs[String(level)] || 500;
  const deadline = Date.now() + budget;
  const moves = legalMoves(board, side);
  if (!moves.length) return null;

  if (level <= 1) return pickRandom(moves);
  if (level === 2) {
    const captures = moves.filter((move) => move.captured);
    return captures.length
      ? captures.sort((a, b) => mvvLva(b, board) - mvvLva(a, board))[0]
      : pickRandom(moves);
  }

  if (config.ordering === 'iterative-deepening') {
    let bestMove = null;
    for (let d = 1; d <= config.depth; d += 1) {
      const result = searchRoot(board, side, d, config, deadline);
      if (result.aborted) break;
      if (result.move) bestMove = result.move;
      if (Date.now() > deadline) break;
    }
    return bestMove || moves[0];
  }

  return searchRoot(board, side, config.depth, config, deadline).move || moves[0];
}

module.exports = {
  RED, BLACK, EMPTY, ROWS, COLS, CHARS, VALUE, MATE,
  INITIAL_BOARD, INITIAL_FEN, GAME_OVER, HALFMOVE_DRAW_LIMIT,
  engineProfile, levelNotes,
  cloneBoard, colorOf, otherSide, inBoard, inPalace,
  legalMoves, pseudoMovesFor, applyMove, isInCheck, isFlyingGeneral, findKing,
  toFen, parseFen, positionKey, moveToUci, uciToMove,
  gameOver, evaluate, redWinRate, localBestMove,
};
