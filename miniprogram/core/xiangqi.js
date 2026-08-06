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
const VALUE = { K:10000, A:20, B:20, N:45, R:90, C:50, P:10 };
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
    [[-2,-1,-1,0],[-2,1,-1,0],[2,-1,1,0],[2,1,1,0],[-1,-2,0,-1],[1,-2,0,-1],[-1,2,0,1],[1,2,0,1]].forEach(([dr,dc,lr,lc]) => { if (!board[row+lr]?.[col+lc]) pushIfOpen(board,moves,from,row+dr,col+dc,color,capturesOnly); });
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
function legalMoves(board, side, onlyFrom) {
  const result=[];
  for(let row=0;row<10;row++) for(let col=0;col<9;col++) {
    if(colorOf(board[row][col])!==side || (onlyFrom && (onlyFrom.row!==row || onlyFrom.col!==col))) continue;
    pseudoMovesFor(board,row,col).forEach((move)=>{ if(!isInCheck(applyMove(board,move),side)) result.push(move); });
  }
  return result;
}
function toFen(board, side) { return `${board.map((row)=>{let empty=0,out=''; row.forEach((piece)=>{if(!piece) empty++; else {if(empty){out+=empty;empty=0;} out+=piece;}}); return out+(empty||'');}).join('/')} ${side===RED?'w':'b'} - - 0 1`; }
function moveLabel(move, board) { const piece=board[move.from.row][move.from.col]; return `${CHARS[piece]} ${move.from.col+1}${10-move.from.row} → ${move.to.col+1}${10-move.to.row}`; }
function localBestMove(board, side) { const moves=legalMoves(board,side); return moves.sort((a,b)=>(VALUE[(b.captured||'').toUpperCase()]||0)-(VALUE[(a.captured||'').toUpperCase()]||0))[0] || null; }
module.exports = { RED, BLACK, CHARS, INITIAL_BOARD, cloneBoard, colorOf, legalMoves, applyMove, isInCheck, toFen, moveLabel, localBestMove };
