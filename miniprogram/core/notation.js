/**
 * 中文着法记谱, 逐条对应 Android 的 Move.toChineseString。
 *
 * 记谱约定:
 * - 红方用汉字数字, 列号自红方右侧(col 8)起数; 黑方用阿拉伯数字, 列号自黑方右侧(col 0)起数。
 * - 进 = 朝对方前进(红方 row 减小, 黑方 row 增大), 退 = 后撤, 平 = 横移。
 * - 车/炮/兵/将 进退记步数; 马/相/仕 走斜线, 进退记目标列。
 * - 同一列有多个同种棋子时改用 前/中/后 前缀替代起始列。
 *
 * 之前小程序显示的是 "车 15 → 25" 这种坐标串 —— 那不是象棋记谱, 复盘时对不上
 * 任何棋谱书, 也和 App 里的"炮二平五"两回事。
 */
const { CHARS, ROWS, COLS } = require('./xiangqi');

const CHINESE_DIGITS = ['〇', '一', '二', '三', '四', '五', '六', '七', '八', '九'];

/** 走斜线的子: 仕/相/马 —— 它们的进退记目标列而不是步数 */
const DIAGONAL_MOVERS = new Set(['A', 'B', 'N']);

function numeral(n, isRed) {
  return isRed && n >= 0 && n < CHINESE_DIGITS.length ? CHINESE_DIGITS[n] : String(n);
}

/** 列号: 红方自右向左数(col 8 = 一), 黑方自右向左数(col 0 = 1) */
function fileNumber(col, isRed) {
  return isRed ? numeral(COLS - col, true) : numeral(col + 1, false);
}

/**
 * @param move  `{ from: {row, col}, to: {row, col} }`
 * @param board 走这一步**之前**的棋盘 —— 走完棋盘就变了, 记谱必须按走之前的算
 */
function toChinese(move, board) {
  const piece = board[move.from.row] && board[move.from.row][move.from.col];
  if (!piece) return '';

  const isRed = piece === piece.toUpperCase();
  const name = CHARS[piece];
  const type = piece.toUpperCase();

  const fr = move.from.row;
  const fc = move.from.col;
  const tr = move.to.row;
  const tc = move.to.col;

  // 同列同种棋子, 按"从前到后"排序: 红方前 = row 小, 黑方前 = row 大
  const sameFile = [];
  for (let row = 0; row < ROWS; row += 1) {
    if (board[row][fc] === piece) sameFile.push(row);
  }
  sameFile.sort((a, b) => (isRed ? a - b : b - a));

  const index = sameFile.indexOf(fr);
  let prefix = '';
  if (sameFile.length >= 2 && index >= 0) {
    if (index === 0) prefix = '前';
    else if (index === sameFile.length - 1) prefix = '后';
    else if (sameFile.length === 3) prefix = '中';
    else prefix = numeral(index + 1, isRed);
  }

  let action;
  if (tr === fr) action = '平';
  else if ((tr < fr) === isRed) action = '进';
  else action = '退';

  const target = action === '平' || DIAGONAL_MOVERS.has(type)
    ? fileNumber(tc, isRed)
    : numeral(Math.abs(tr - fr), isRed);

  const origin = prefix ? '' : fileNumber(fc, isRed);
  return `${prefix}${name}${origin}${action}${target}`;
}

module.exports = { toChinese };
