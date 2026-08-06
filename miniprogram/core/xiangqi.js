const { engineProfile, levelNotes } = require('../generated/product-config');

const RED = 1;
const BLACK = 0;
const EMPTY = '';
const INITIAL_BOARD = [
  ['r','n','b','a','k','a','b','n','r'],
  ['','','','','','','','',''],
  ['','c','','','','','','c',''],
  ['p','','p','','p','','p','','p'],
  ['','','','','','','','',''],
  ['','','','','','','','',''],
  ['P','','P','','P','','P','','P'],
  ['','C','','','','','','C',''],
  ['','','','','','','','',''],
  ['R','N','B','A','K','A','B','N','R']
];
const CHARS = { K:'帅', A:'仕', B:'相', N:'马', R:'车', C:'炮', P:'兵', k:'将', a:'士', b:'象', n:'马', r:'车', c:'炮', p:'卒' };
const VALUE = engineProfile.pieceValues;
const MATE = 100000;
const cloneBoard = (board) => board.map((row) => row.slice());
const colorOf = (piece) => !piece ? -1 : piece === piece.toUpperCase() ? RED : BLACK;
const inBoard = (row, col) => row >= 0 && row < 10 && col >= 0 && col < 9;
const inPalace = (row, col, color) => col >= 3 && col <= 5 && (color === RED ? row >= 7 && row <= 9 : row >= 0 && row <= 2);
const crossedRiver = (row, color) => color === RED ? row <= 4 : row >= 5;
function pushIfOpen(board, moves, from, row, col, color, capturesOnly = false) {
  if (!inBoard(row, col)) return;
  const target = board[row][col];
  if (colorOf(target) === color) return;
  if (!capturesOnly || target) moves.push({ from, to: { row, col }, captured: target || EMPTY });
}
function pseudoMovesFor(board, row, col, capturesOnly = false) {
  const piece = board[row][col];
  if (!piece) return [];
  const color = colorOf(piece);
  const type = piece.toUpperCase();
  const moves = [];
  const from = { row, col };
  if (type === 'K') {
    [[-1,0],[1,0],[0,-1],[0,1]].forEach(([dr,dc]) => { if (inPalace(row+dr,col+dc,color)) pushIfOpen(board,moves,from,row+dr,col+dc,color,capturesOnly); });
  } else if (type === 'A') {
    [[-1,-1],[-1,1],[1,-1],[1,1]].forEach(([dr,dc]) => { if (inPalace(row+dr,col+dc,color)) pushIfOpen(board,moves,from,row+dr,col+dc,color,capturesOnly); });
  } else if (type === 'B') {
    [[-2,-2],[-2,2],[2,-2],[2,2]].forEach(([dr,dc]) => { const tr=row+dr, tc=col+dc; const ownSide=color===RED?tr>=5:tr<=4; if (ownSide && inBoard(tr,tc) && !board[row+dr/2][col+dc/2]) pushIfOpen(board,moves,from,tr,tc,color,capturesOnly); });
  } else if (type === 'N') {
    [[-2,-1,-1,0],[-2,1,-1,0],[2,-1,1,0],[2,1,1,0],[-1,-2,0,-1],[1,-2,0,-1],[-1,2,0,1],[1,2,0,1]].forEach(([dr,dc,lr,lc]) => { if(!board[row+lr]?.[col+lc]) pushIfOpen(board,moves,from,row+dr,col+dc,color,capturesOnly); });
  } else if (type === 'R' || type === 'C') {
    [[-1,0],[1,0],[0,-1],[0,1]].forEach(([dr,dc]) => {
      let tr=row+dr, tc=col+dc, screen=false;
      while (inBoard(tr,tc)) {
        const target=board[tr][tc];
        if (type === 'R') {
          if (!target) { if (!capturesOnly) moves.push({from,to:{row:tr,col:tc},captured:EMPTY}); }
          else { if (colorOf(target)!==color) moves.push({from,to:{row:tr,col:tc},captured:target}); break; }
        } else if (!screen) {
          if (!target) { if (!capturesOnly) moves.push({from,to:{row:tr,col:tc},captured:EMPTY}); } else screen=true;
        } else if (target) { if (colorOf(target)!==color) moves.push({from,to:{row:tr,col:tc},captured:target}); break; }
        tr+=dr; tc+=dc;
      }
    });
  } else if (type === 'P') {
    const forward = color === RED ? -1 : 1;
    pushIfOpen(board,moves,from,row+forward,col,color,capturesOnly);
    if (crossedRiver(row,color)) { pushIfOpen(board,moves,from,row,col-1,color,capturesOnly); pushIfOpen(board,moves,from,row,col+1,color,capturesOnly); }
  }
  return moves;
}
function applyMove(board, move) { const next=cloneBoard(board); next[move.to.row][move.to.col]=next[move.from.row][move.from.col]; next[move.from.row][move.from.col]=EMPTY; return next; }
function findKing(board, color) { const king=color===RED?'K':'k'; for(let row=0;row<10;row++) for(let col=0;col<9;col++) if(board[row][col]===king) return {row,col}; return null; }
function isInCheck(board, color) {
  const king=findKing(board,color); if(!king) return true;
  const enemy=color===RED?BLACK:RED;
  for(let row=0;row<10;row++) for(let col=0;col<9;col++) if(colorOf(board[row][col])===enemy) {
    if(pseudoMovesFor(board,row,col,true).some((move)=>move.to.row===king.row&&move.to.col===king.col)) return true;
  }
  return false;
}
function legalMoves(board, side, onlyFrom, capturesOnly = false) {
  const result=[];
  for(let row=0;row<10;row++) for(let col=0;col<9;col++) {
    if(colorOf(board[row][col])!==side || (onlyFrom && (onlyFrom.row!==row || onlyFrom.col!==col))) continue;
    pseudoMovesFor(board,row,col,capturesOnly).forEach((move)=>{ if(!isInCheck(applyMove(board,move),side)) result.push(move); });
  }
  return result;
}
function toFen(board, side) { return `${board.map((row)=>{let empty=0,out=''; row.forEach((piece)=>{if(!piece) empty++; else {if(empty){out+=empty;empty=0;} out+=piece;}}); return out+(empty||'');}).join('/')} ${side===RED?'w':'b'} - - 0 1`; }
function moveLabel(move, board) { const piece=board[move.from.row][move.from.col]; return `${CHARS[piece]} ${move.from.col+1}${10-move.from.row} → ${move.to.col+1}${10-move.to.row}`; }
function pstBonus(piece, row, col) {
  const type = piece.toUpperCase();
  const red = piece === piece.toUpperCase();
  const r = red ? row : 9 - row;
  switch (type) {
    case 'K': return r >= 7 ? (col === 4 ? 6 : 0) : -20;
    case 'A': return r >= 7 ? 0 : -20;
    case 'B': {
      const corner = (col === 0 || col === 8) && (r === 0 || r === 4 || r === 5 || r === 9);
      return corner ? 4 : 0;
    }
    case 'N': {
      const center = Math.max(0, 3 - Math.abs(col - 4));
      const active = r < 5;
      return center * (active ? 8 : 4);
    }
    case 'R': return r === 4 || r === 5 ? 12 : 0;
    case 'C': return r === 1 || r === 2 || r === 7 || r === 8 ? 6 : 0;
    case 'P': {
      if (r >= 5) return 0;
      if (r === 4) return 30;
      if (r === 3) return 50;
      if (r === 2) return 60;
      return 75;
    }
    default: return 0;
  }
}
function evaluate(board, side) {
  let score = 0;
  for (let r = 0; r < 10; r += 1) for (let c = 0; c < 9; c += 1) {
    const piece = board[r][c];
    if (!piece) continue;
    const v = VALUE[piece.toUpperCase()] + pstBonus(piece, r, c);
    score += piece === piece.toUpperCase() ? v : -v;
  }
  return side === RED ? score : -score;
}
function mvvLva(move, board) {
  const victim = move.captured;
  if (!victim) return 0;
  const attacker = board[move.from.row][move.from.col];
  return VALUE[victim.toUpperCase()] * 10 - VALUE[attacker.toUpperCase()];
}
function orderMoves(moves, board, killers, history) {
  return moves.slice().sort((a, b) => {
    const ca = mvvLva(a, board);
    const cb = mvvLva(b, board);
    if (ca !== cb) return cb - ca;
    const ka = killers ? killers.indexOf(`${a.from.row},${a.from.col}->${a.to.row},${a.to.col}`) : -1;
    const kb = killers ? killers.indexOf(`${b.from.row},${b.from.col}->${b.to.row},${b.to.col}`) : -1;
    if (ka !== kb) return ka - kb;
    const ha = history?.[`${a.from.row},${a.from.col}->${a.to.row},${a.to.col}`] || 0;
    const hb = history?.[`${b.from.row},${b.from.col}->${b.to.row},${b.to.col}`] || 0;
    return hb - ha;
  });
}
function makeKey(move) { return `${move.from.row},${move.from.col}->${move.to.row},${move.to.col}`; }
function search(board, side, depth, alpha, beta, deadline, ordering, ply) {
  if (Date.now() > deadline) return { score: 0, aborted: true };
  const inCheck = isInCheck(board, side);
  if (inCheck) depth += 1;
  if (depth <= 0) {
    if (!ordering.quiescence) return { score: evaluate(board, side), aborted: false };
    return quiesce(board, side, alpha, beta, deadline);
  }
  const moves = legalMoves(board, side);
  if (!moves.length) {
    if (inCheck) return { score: -MATE + ply, aborted: false };
    return { score: 0, aborted: false };
  }
  const killers = ply >= 0 ? [] : null;
  const ordered = orderMoves(moves, board, killers);
  let best = -MATE;
  for (const move of ordered) {
    const next = applyMove(board, move);
    const child = search(next, side ^ 1, depth - 1, -beta, -alpha, deadline, ordering, ply + 1);
    if (child.aborted) return child;
    const score = -child.score;
    if (score > best) best = score;
    if (score > alpha) alpha = score;
    if (alpha >= beta) {
      if (!move.captured && killers) killers.push(makeKey(move));
      break;
    }
  }
  return { score: best, aborted: false };
}
function quiesce(board, side, alpha, beta, deadline) {
  if (Date.now() > deadline) return { score: 0, aborted: true };
  const stand = evaluate(board, side);
  if (stand >= beta) return { score: beta, aborted: false };
  if (stand > alpha) alpha = stand;
  const moves = legalMoves(board, side, undefined, true).sort((a, b) => mvvLva(b, board) - mvvLva(a, board));
  for (const move of moves) {
    const next = applyMove(board, move);
    const child = quiesce(next, side ^ 1, -beta, -alpha, deadline);
    if (child.aborted) return child;
    const score = -child.score;
    if (score >= beta) return { score: beta, aborted: false };
    if (score > alpha) alpha = score;
  }
  return { score: alpha, aborted: false };
}
function searchRoot(board, side, depth, ordering, deadline) {
  const moves = legalMoves(board, side);
  if (!moves.length) return { move: null, aborted: false };
  const ordered = orderMoves(moves, board, []);
  let bestMove = ordered[0];
  let bestScore = -MATE;
  let alpha = -MATE;
  const beta = MATE;
  for (const move of ordered) {
    const next = applyMove(board, move);
    const child = search(next, side ^ 1, depth - 1, -beta, -alpha, deadline, ordering, 0);
    if (child.aborted) return { move: bestMove, aborted: true };
    const score = -child.score;
    if (score > bestScore) { bestScore = score; bestMove = move; }
    if (score > alpha) alpha = score;
  }
  return { move: bestMove, aborted: false };
}
function pickRandom(moves) { return moves[Math.floor(Math.random() * moves.length)]; }
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
    return captures.length ? captures.sort((a, b) => mvvLva(b, board) - mvvLva(a, board))[0] : pickRandom(moves);
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
  const result = searchRoot(board, side, config.depth, config, deadline);
  return result.move || moves[0];
}
module.exports = {
  RED, BLACK, CHARS, INITIAL_BOARD, VALUE, engineProfile, levelNotes,
  cloneBoard, colorOf, legalMoves, applyMove, isInCheck, toFen, moveLabel, evaluate, localBestMove
};
