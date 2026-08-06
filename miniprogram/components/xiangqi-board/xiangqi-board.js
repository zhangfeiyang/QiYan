/**
 * 棋盘 —— canvas 2d 重画, 对应 Android 的 BoardView.kt。
 *
 * 换掉原来那版 DOM 棋盘的原因很直接: 它把棋子画在**格子**里, 而象棋的子落在
 * **交点**上。9 列 × 10 行的格子拼出来的是一张 9×10 的方格纸, 线和子对不上,
 * 一眼就不是象棋盘。这里按 BoardView 的做法画 8×9 个格、9×10 个交点。
 *
 * 绘制顺序与 BoardView.onDraw 保持一致:
 *   木纹底 → 网格 → 九宫 → 楚河汉界 → 坐标 → 高亮 → 棋子 → 选中环
 *   → 走子动画 → 提示箭头 → 吃子特效
 * 顺序不是随意的: 选中环必须压在棋子**之上**(否则点了子看不出选中),
 * 提示箭头必须压在所有棋子之上(否则被挡住就白提示了)。
 */
const { CHARS, colorOf, RED, ROWS, COLS } = require('../../core/xiangqi');
const palette = require('../../core/palette');

/** 棋盘四周为棋子半径和坐标文字预留的空间, 单位是"格" (同 BoardView.MARGIN_CELLS) */
const MARGIN_CELLS = 1.7;
const MOVE_ANIMATION_MS = 220;

/** 将/帅被吃是终局, 值得演久一点; 兵卒频繁被吃, 拖长了会烦 */
function captureFxDuration(piece) {
  const type = piece.toUpperCase();
  if (type === 'K') return 1100;
  if (type === 'R' || type === 'C') return 780;
  if (type === 'P') return 420;
  return 620;
}

function shardCount(type) {
  if (type === 'P') return 8;
  if (type === 'K') return 26;
  if (type === 'R') return 18;
  return 14;
}

/** 与 Android 一样用固定种子: 同一个子在同一格被吃, 每次碎片形状一致 */
function seededRandom(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0;
    return state / 4294967296;
  };
}

Component({
  properties: {
    board: { type: Array, value: [] },
    selected: { type: Object, value: null },
    /** 选中子的可走点, `[{row, col}]` */
    targets: { type: Array, value: [] },
    /** 上一步走法 `{from, to}` */
    lastMove: { type: Object, value: null },
    /** 被将的将/帅所在格 */
    checkedKing: { type: Object, value: null },
    /** 提示着法 `{from, to}` */
    hintMove: { type: Object, value: null },
    /** 提示的备选着法 */
    hintSecondary: { type: Array, value: [] },
    flipped: { type: Boolean, value: false },
    showCoordinates: { type: Boolean, value: true },
    animation: { type: Boolean, value: true },
    theme: { type: String, value: 'dark' },
    disabled: { type: Boolean, value: false },
  },

  observers: {
    'board, selected, targets, lastMove, checkedKing, hintMove, hintSecondary, flipped, showCoordinates, theme'() {
      this.requestDraw();
    },
  },

  lifetimes: {
    attached() {
      this._geom = null;
      this._anim = null;
      this._fx = null;
      this._selectionPulse = 0;
      this._rafId = 0;
    },
    ready() {
      this.initCanvas();
    },
    detached() {
      this.stopLoop();
    },
  },

  methods: {
    // ====== 初始化 ======

    initCanvas() {
      wx.createSelectorQuery()
        .in(this)
        .select('#board')
        .fields({ node: true, size: true })
        .exec((res) => {
          const item = res && res[0];
          if (!item || !item.node) return;
          const canvas = item.node;
          const ctx = canvas.getContext('2d');
          const dpr = wx.getSystemInfoSync().pixelRatio || 2;
          canvas.width = item.width * dpr;
          canvas.height = item.height * dpr;
          ctx.scale(dpr, dpr);

          this._canvas = canvas;
          this._ctx = ctx;
          this._width = item.width;
          this._height = item.height;
          this.calculateDimensions();
          this.draw();
        });
    },

    /**
     * 网格是 8 格宽 × 9 格高(交点 9×10)。棋子画在最外圈交点上会向外凸出一个
     * 半径, 所以四周必须额外留出 MARGIN_CELLS 格, 否则边线上的车/兵会被裁掉。
     */
    calculateDimensions() {
      const cell = Math.min(
        this._width / (COLS - 1 + MARGIN_CELLS),
        this._height / (ROWS - 1 + MARGIN_CELLS)
      );
      const bw = cell * (COLS - 1);
      const bh = cell * (ROWS - 1);
      this._geom = {
        cell,
        left: (this._width - bw) / 2,
        top: (this._height - bh) / 2,
        right: (this._width - bw) / 2 + bw,
        bottom: (this._height - bh) / 2 + bh,
        radius: cell * 0.42,
      };
    },

    // ====== 坐标 ======

    center(row, col) {
      const g = this._geom;
      const dRow = this.data.flipped ? ROWS - 1 - row : row;
      const dCol = this.data.flipped ? COLS - 1 - col : col;
      return { x: g.left + dCol * g.cell, y: g.top + dRow * g.cell };
    },

    /** 屏幕坐标 → 最近的交点; 超过 0.6 格就当没点中, 免得误落子 */
    pixelToCell(x, y) {
      const g = this._geom;
      if (!g) return null;
      const dCol = Math.round((x - g.left) / g.cell);
      const dRow = Math.round((y - g.top) / g.cell);
      if (dCol < 0 || dCol >= COLS || dRow < 0 || dRow >= ROWS) return null;
      const cx = g.left + dCol * g.cell;
      const cy = g.top + dRow * g.cell;
      if (Math.hypot(x - cx, y - cy) > g.cell * 0.6) return null;
      return {
        row: this.data.flipped ? ROWS - 1 - dRow : dRow,
        col: this.data.flipped ? COLS - 1 - dCol : dCol,
      };
    },

    /**
     * 落子。用 touchend 而不是 tap: canvas 的 tap 事件给的是相对页面的坐标,
     * 而 changedTouches[0].x/y 本来就是相对 canvas 的, 不用再自己减偏移。
     */
    onTouchEnd(event) {
      if (this.data.disabled) return;
      const touch = event.changedTouches && event.changedTouches[0];
      if (!touch) return;
      const cell = this.pixelToCell(touch.x, touch.y);
      if (cell) this.triggerEvent('celltap', cell);
    },

    // ====== 对外动画接口 ======

    /**
     * 播一次走子动画: 起点的子沿直线滑到终点。
     *
     * @param piece 移动的那枚子。由调用方传进来而不是从 board 里查 —— 调用
     *              这个方法时棋盘已经更新过了, 起点格是空的。
     */
    playMove(move, piece) {
      if (!this.data.animation || !move || !piece || !this._geom) return;
      this._anim = { move, piece, start: Date.now(), progress: 0 };
      this.startLoop();
    },

    /** 播一次吃子特效。七个兵种七套视觉, 与 BoardView.drawCaptureFx 对应 */
    playCaptureEffect(square, capturedPiece) {
      if (!capturedPiece || !this._geom) return;
      const type = capturedPiece.toUpperCase();
      const colors = palette.get(this.data.theme);
      const warm = colorOf(capturedPiece) === RED ? colors.pieceRed : colors.pieceBlack;
      const rand = seededRandom(capturedPiece.charCodeAt(0) * 31 + square.row * 9 + square.col);
      const count = shardCount(type);
      const shards = [];
      for (let i = 0; i < count; i += 1) {
        const ang = (i / count) * Math.PI * 2 + rand() * 0.4;
        const speed = 0.5 + rand() * 0.9;
        shards.push({
          vx: Math.cos(ang) * speed,
          vy: Math.sin(ang) * speed,
          spin: (rand() - 0.5) * 720,
          size: 0.06 + rand() * 0.1,
          color: i % 3 === 0 ? colors.fxSpark : warm,
        });
      }
      this._fx = {
        square, piece: capturedPiece, shards,
        start: Date.now(), duration: captureFxDuration(capturedPiece),
      };
      this.startLoop();
    },

    // ====== 绘制循环 ======

    requestDraw() {
      if (this._ctx) this.draw();
    },

    startLoop() {
      if (this._rafId || !this._canvas) return;
      const tick = () => {
        const busy = this.step();
        this.draw();
        if (busy) {
          this._rafId = this._canvas.requestAnimationFrame(tick);
        } else {
          this._rafId = 0;
        }
      };
      this._rafId = this._canvas.requestAnimationFrame(tick);
    },

    stopLoop() {
      if (this._rafId && this._canvas) this._canvas.cancelAnimationFrame(this._rafId);
      this._rafId = 0;
    },

    /** 推进各条动画, 返回"还需要继续画吗" */
    step() {
      const now = Date.now();
      let busy = false;

      if (this._anim) {
        const p = (now - this._anim.start) / MOVE_ANIMATION_MS;
        if (p >= 1) this._anim = null;
        else {
          this._anim.progress = p;
          busy = true;
        }
      }
      if (this._fx) {
        const p = (now - this._fx.start) / this._fx.duration;
        if (p >= 1) this._fx = null;
        else {
          // 减速曲线, 与 Android 的 DecelerateInterpolator 同感觉
          this._fx.progress = 1 - (1 - p) * (1 - p);
          busy = true;
        }
      }
      if (this.data.selected) {
        // 呼吸环: 静态描边在木色棋盘上容易被看漏, 动起来才抓得住眼睛
        this._selectionPulse = (Math.sin(now / 500) + 1) / 2;
        busy = true;
      }
      return busy;
    },

    draw() {
      const ctx = this._ctx;
      const g = this._geom;
      if (!ctx || !g) return;
      const c = palette.get(this.data.theme);

      ctx.clearRect(0, 0, this._width, this._height);
      this.drawBoard(ctx, g, c);
      this.drawGrid(ctx, g, c);
      this.drawPalace(ctx, g, c);
      this.drawRiver(ctx, g, c);
      if (this.data.showCoordinates) this.drawCoordinates(ctx, g, c);
      this.drawHighlights(ctx, g, c);
      this.drawPieces(ctx, g, c);
      this.drawSelectionRing(ctx, g, c);
      if (this._anim) this.drawAnimatingPiece(ctx, g, c);
      this.drawHintOverlay(ctx, g, c);
      if (this._fx) this.drawCaptureFx(ctx, g, c);
    },

    // ====== 棋盘本体 ======

    drawBoard(ctx, g, c) {
      const pad = g.radius * 1.2;
      const rect = { x: g.left - pad, y: g.top - pad, w: g.right - g.left + pad * 2, h: g.bottom - g.top + pad * 2 };
      this._boardRect = rect;

      const grad = ctx.createLinearGradient(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h);
      grad.addColorStop(0, c.boardGrad[0]);
      grad.addColorStop(0.4, c.boardGrad[1]);
      grad.addColorStop(0.7, c.boardGrad[2]);
      grad.addColorStop(1, c.boardGrad[3]);

      roundRect(ctx, rect.x, rect.y, rect.w, rect.h, 6);
      ctx.fillStyle = grad;
      ctx.fill();

      // 木纹细线
      ctx.strokeStyle = c.boardGrain;
      ctx.lineWidth = 0.5;
      for (let i = 0; i <= 30; i += 1) {
        const y = rect.y + (rect.h / 30) * i;
        const wave = Math.sin(i * 0.7) * 2;
        ctx.beginPath();
        ctx.moveTo(rect.x + wave, y);
        ctx.lineTo(rect.x + rect.w - wave, y);
        ctx.stroke();
      }

      roundRect(ctx, rect.x, rect.y, rect.w, rect.h, 6);
      ctx.strokeStyle = c.boardBorder;
      ctx.lineWidth = 2;
      ctx.stroke();
    },

    drawGrid(ctx, g, c) {
      ctx.strokeStyle = c.boardLine;
      ctx.lineWidth = 1;

      for (let i = 0; i < ROWS; i += 1) {
        const y = g.top + i * g.cell;
        line(ctx, g.left, y, g.right, y);
      }
      for (let i = 0; i < COLS; i += 1) {
        const x = g.left + i * g.cell;
        if (i === 0 || i === COLS - 1) {
          line(ctx, x, g.top, x, g.bottom);
        } else {
          // 中间的纵线在楚河汉界处断开
          line(ctx, x, g.top, x, g.top + 4 * g.cell);
          line(ctx, x, g.top + 5 * g.cell, x, g.bottom);
        }
      }
    },

    drawPalace(ctx, g, c) {
      ctx.strokeStyle = c.boardLine;
      ctx.lineWidth = 1;
      const l = g.left + 3 * g.cell;
      const r = g.left + 5 * g.cell;
      for (const topY of [g.top, g.top + 7 * g.cell]) {
        line(ctx, l, topY, r, topY + 2 * g.cell);
        line(ctx, r, topY, l, topY + 2 * g.cell);
      }
    },

    drawRiver(ctx, g, c) {
      const centerY = g.top + 4.5 * g.cell;
      ctx.fillStyle = c.boardPalace;
      ctx.font = `bold ${g.radius * 1.1}px serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('楚 河          汉 界', (g.left + g.right) / 2, centerY);
    },

    /**
     * 列号从**底部一方**的右侧数起, 上下底都标:
     *   红在底部(未翻转): 屏幕右列 = 1 ... 左列 = 9
     *   黑在底部(已翻转): 屏幕左列 = 1 ... 右列 = 9
     * 这样棋盘上标的数字和嘴里念的"车九进四""炮二平五"一一对应。
     */
    drawCoordinates(ctx, g, c) {
      const redAtBottom = !this.data.flipped;
      ctx.fillStyle = c.boardCoord;
      ctx.font = `${g.radius * 0.65}px serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';

      for (let col = 0; col < COLS; col += 1) {
        const x = g.left + col * g.cell;
        const label = String(redAtBottom ? COLS - col : col + 1);
        ctx.fillText(label, x, g.top - g.radius * 0.9);
        ctx.fillText(label, x, g.bottom + g.radius * 0.9);
      }
      for (let row = 0; row < ROWS; row += 1) {
        const y = g.top + row * g.cell;
        const label = String(this.data.flipped ? row + 1 : ROWS - row);
        ctx.fillText(label, g.left - g.radius * 1.1, y);
        ctx.fillText(label, g.right + g.radius * 1.1, y);
      }
    },

    // ====== 高亮 ======

    drawHighlights(ctx, g, c) {
      const { lastMove, selected, targets, hintSecondary, checkedKing, board } = this.data;

      if (lastMove && lastMove.from) {
        ctx.strokeStyle = c.hlLast;
        ctx.lineWidth = 2;
        this.ring(ctx, g, lastMove.from, g.radius * 0.95);
        this.ring(ctx, g, lastMove.to, g.radius * 0.95);
      }
      if (selected) {
        ctx.strokeStyle = c.hlSelect;
        ctx.lineWidth = 3;
        this.ring(ctx, g, selected, g.radius * 0.95);
      }
      for (const target of targets || []) {
        const occupied = board[target.row] && board[target.row][target.col];
        if (occupied) this.drawCaptureMarker(ctx, g, c, target);
        else this.drawMoveMarker(ctx, g, c, target);
      }
      for (const move of hintSecondary || []) {
        ctx.strokeStyle = c.hlHintSoft;
        ctx.lineWidth = 1.5;
        this.ring(ctx, g, move.from, g.radius * 0.95);
        this.ring(ctx, g, move.to, g.radius * 0.95);
      }
      if (checkedKing) {
        ctx.strokeStyle = c.hlCheck;
        ctx.lineWidth = 4;
        this.ring(ctx, g, checkedKing, g.radius * 0.95);
      }
    },

    ring(ctx, g, cell, radius) {
      const p = this.center(cell.row, cell.col);
      ctx.beginPath();
      ctx.arc(p.x, p.y, radius, 0, Math.PI * 2);
      ctx.stroke();
    },

    drawMoveMarker(ctx, g, c, cell) {
      const p = this.center(cell.row, cell.col);
      const r = g.radius * 0.18;
      ctx.fillStyle = c.hlMoveHalo;
      circle(ctx, p.x, p.y, r * 1.8);
      ctx.fillStyle = c.hlMove;
      circle(ctx, p.x, p.y, r);
    },

    /** 可吃的目标画四角小点, 与"可以走过去"的实心点区分开 */
    drawCaptureMarker(ctx, g, c, cell) {
      const p = this.center(cell.row, cell.col);
      const radius = g.radius * 0.95;
      ctx.fillStyle = c.hlCapture;
      for (const frac of [0.4, 0.6]) {
        for (const sign of [-1, 1]) {
          const off = radius * frac * sign;
          circle(ctx, p.x + off, p.y + off, radius * 0.12);
        }
      }
    },

    /**
     * 选中环画在棋子**之上**, 并且比棋子大一圈, 保证露在外面。
     * 四角小标记: 即使用户对颜色不敏感, 那四个角也能说明"这一个被选中了"。
     */
    drawSelectionRing(ctx, g, c) {
      const sel = this.data.selected;
      if (!sel) return;
      const p = this.center(sel.row, sel.col);
      const phase = this._selectionPulse;
      const ringR = g.radius * (1.06 + 0.1 * phase);

      ctx.globalAlpha = 0.27 * (1 - phase * 0.5);
      ctx.strokeStyle = c.selRing;
      ctx.lineWidth = g.radius * 0.3;
      ctx.beginPath();
      ctx.arc(p.x, p.y, ringR, 0, Math.PI * 2);
      ctx.stroke();
      ctx.globalAlpha = 1;

      ctx.lineWidth = g.radius * 0.13;
      ctx.beginPath();
      ctx.arc(p.x, p.y, ringR, 0, Math.PI * 2);
      ctx.stroke();

      const corner = g.radius * 1.34;
      const len = g.radius * 0.34;
      ctx.lineWidth = g.radius * 0.11;
      for (const sx of [-1, 1]) {
        for (const sy of [-1, 1]) {
          const x = p.x + sx * corner;
          const y = p.y + sy * corner;
          line(ctx, x, y, x - sx * len, y);
          line(ctx, x, y, x, y - sy * len);
        }
      }
    },

    // ====== 棋子 ======

    drawPieces(ctx, g, c) {
      const board = this.data.board || [];
      const skip = this._anim ? this._anim.move.to : null;
      for (let row = 0; row < ROWS; row += 1) {
        for (let col = 0; col < COLS; col += 1) {
          const piece = board[row] && board[row][col];
          if (!piece) continue;
          if (skip && skip.row === row && skip.col === col) continue;
          const p = this.center(row, col);
          this.drawPieceAt(ctx, g, c, p.x, p.y, piece, 1);
        }
      }
    },

    drawAnimatingPiece(ctx, g, c) {
      const { move, piece, progress } = this._anim;
      const from = this.center(move.from.row, move.from.col);
      const to = this.center(move.to.row, move.to.col);
      this.drawPieceAt(
        ctx, g, c,
        from.x + (to.x - from.x) * progress,
        from.y + (to.y - from.y) * progress,
        piece, 1
      );
    },

    drawPieceAt(ctx, g, c, cx, cy, piece, alpha) {
      const isRed = colorOf(piece) === RED;
      const r = g.radius;
      ctx.globalAlpha = alpha;

      // 阴影
      ctx.fillStyle = c.pieceShadow;
      circle(ctx, cx + 1.5, cy + 2, r * 1.05);

      // 外圈
      ctx.fillStyle = isRed ? c.pieceRedEdge : c.pieceBlack;
      circle(ctx, cx, cy, r);

      // 内圈: 左上亮 → 右下暗, 做出圆润的立体感
      const grad = ctx.createRadialGradient(
        cx - r * 0.3, cy - r * 0.3, 0,
        cx - r * 0.3, cy - r * 0.3, r * 0.7
      );
      grad.addColorStop(0, c.pieceGrad[0]);
      grad.addColorStop(0.5, c.pieceGrad[1]);
      grad.addColorStop(1, c.pieceGrad[2]);
      ctx.fillStyle = grad;
      circle(ctx, cx, cy, r * 0.92);

      // 木纹
      ctx.strokeStyle = c.pieceGrain;
      ctx.lineWidth = 0.5;
      ctx.save();
      ctx.beginPath();
      ctx.arc(cx, cy, r * 0.92, 0, Math.PI * 2);
      ctx.clip();
      for (let i = -2; i <= 2; i += 1) {
        const y = cy + i * r * 0.25;
        line(ctx, cx - r, y, cx + r, y);
      }
      ctx.restore();

      // 字
      ctx.fillStyle = isRed ? c.pieceRed : c.pieceBlack;
      ctx.font = `bold ${r * 1.15}px serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(CHARS[piece] || '', cx, cy + r * 0.03);

      ctx.globalAlpha = 1;
    },

    // ====== 提示 ======

    /** 终点画一枚半透明虚影, 起点到终点画一段绿色箭头 */
    drawHintOverlay(ctx, g, c) {
      const hint = this.data.hintMove;
      if (!hint || !hint.from) return;
      const board = this.data.board || [];
      const piece = board[hint.from.row] && board[hint.from.row][hint.from.col];
      if (piece) {
        const to = this.center(hint.to.row, hint.to.col);
        this.drawPieceAt(ctx, g, c, to.x, to.y, piece, 0.35);
      }
      this.drawArrow(ctx, g, c, this.center(hint.from.row, hint.from.col), this.center(hint.to.row, hint.to.col));
    },

    drawArrow(ctx, g, c, from, to) {
      const dx = to.x - from.x;
      const dy = to.y - from.y;
      const len = Math.hypot(dx, dy);
      if (len < 1) return;

      // 两端各留一段, 不要画到棋子内部, 也不要画过终点
      const headLen = g.radius * 0.72;
      const tailGap = g.radius * 0.4;
      const ux = dx / len;
      const uy = dy / len;

      ctx.strokeStyle = c.hlArrow;
      ctx.lineWidth = g.radius * 0.17;
      ctx.lineCap = 'round';
      line(ctx, from.x + ux * tailGap, from.y + uy * tailGap, to.x - ux * headLen, to.y - uy * headLen);
      ctx.lineCap = 'butt';

      const angle = Math.atan2(dy, dx);
      const headAngle = (28 * Math.PI) / 180;
      ctx.fillStyle = c.hlArrow;
      ctx.beginPath();
      ctx.moveTo(to.x, to.y);
      ctx.lineTo(to.x - headLen * Math.cos(angle - headAngle), to.y - headLen * Math.sin(angle - headAngle));
      ctx.lineTo(to.x - headLen * Math.cos(angle + headAngle), to.y - headLen * Math.sin(angle + headAngle));
      ctx.closePath();
      ctx.fill();
    },

    // ====== 吃子特效 ======

    /**
     * 七个兵种七套视觉, 与 BoardView.drawCaptureFx 一一对应:
     *   车  十字冲击波 / 马 螺旋 / 炮 爆炸 / 兵卒 小碎片
     *   士仕 菱形内爆 / 象相 双环扩散 / 将帅 金光爆发
     */
    drawCaptureFx(ctx, g, c) {
      const fx = this._fx;
      const p = this.center(fx.square.row, fx.square.col);
      const t = fx.progress;
      const fade = 1 - t;
      const type = fx.piece.toUpperCase();
      const main = colorOf(fx.piece) === RED ? c.pieceRed : c.pieceBlack;
      const spark = c.fxSpark;
      const r = g.radius;

      ctx.save();
      if (type === 'R') {
        ctx.globalAlpha = fade;
        ctx.strokeStyle = spark;
        ctx.lineCap = 'round';
        ctx.lineWidth = r * 0.42 * fade;
        const reach = r * (1 + 5 * t);
        line(ctx, p.x - reach, p.y, p.x + reach, p.y);
        line(ctx, p.x, p.y - reach, p.x, p.y + reach);
        ctx.globalAlpha = fade * 0.7;
        ctx.strokeStyle = main;
        ctx.lineWidth = r * 0.16 * fade;
        strokeCircle(ctx, p.x, p.y, r * (1 + 2.4 * t));
      } else if (type === 'N') {
        for (let i = 0; i < fx.shards.length; i += 1) {
          const s = fx.shards[i];
          const ang = Math.atan2(s.vy, s.vx) + t * 3.2;
          const dist = r * (0.3 + 2.6 * t) * (0.6 + s.size * 3);
          ctx.globalAlpha = fade;
          ctx.fillStyle = i % 2 === 0 ? spark : main;
          circle(ctx, p.x + Math.cos(ang) * dist, p.y + Math.sin(ang) * dist, r * s.size * (1 - t * 0.6));
        }
      } else if (type === 'C') {
        ctx.globalAlpha = fade;
        ctx.strokeStyle = spark;
        ctx.lineWidth = r * 0.3 * fade;
        strokeCircle(ctx, p.x, p.y, r * (0.6 + 3.4 * t));
        ctx.globalAlpha = fade * 0.6;
        ctx.lineWidth = r * 0.16 * fade;
        strokeCircle(ctx, p.x, p.y, r * (0.4 + 2 * t));
        this.drawShards(ctx, g, p.x, p.y, t, fade, 3.4);
      } else if (type === 'A') {
        ctx.globalAlpha = fade;
        ctx.strokeStyle = spark;
        ctx.lineWidth = r * 0.2;
        const d = r * (2.2 - 1.8 * t);
        ctx.beginPath();
        ctx.moveTo(p.x, p.y - d);
        ctx.lineTo(p.x + d, p.y);
        ctx.lineTo(p.x, p.y + d);
        ctx.lineTo(p.x - d, p.y);
        ctx.closePath();
        ctx.stroke();
      } else if (type === 'B') {
        for (let k = 0; k <= 1; k += 1) {
          const phase = Math.min(1, t + k * 0.35);
          ctx.globalAlpha = 1 - phase;
          ctx.strokeStyle = k === 0 ? spark : main;
          ctx.lineWidth = r * 0.18 * fade;
          const half = r * (0.7 + 2.6 * phase);
          ctx.strokeRect(p.x - half, p.y - half, half * 2, half * 2);
        }
      } else if (type === 'K') {
        const rect = this._boardRect;
        ctx.globalAlpha = fade * 0.22;
        ctx.fillStyle = spark;
        ctx.fillRect(rect.x, rect.y, rect.w, rect.h);

        ctx.globalAlpha = fade;
        ctx.strokeStyle = spark;
        ctx.lineCap = 'round';
        ctx.lineWidth = r * 0.14 * fade;
        const inner = r * (0.5 + 1.2 * t);
        const outer = r * (1.2 + 6 * t);
        for (let i = 0; i < 12; i += 1) {
          const a = (i / 12) * Math.PI * 2 + t * 0.6;
          line(ctx, p.x + Math.cos(a) * inner, p.y + Math.sin(a) * inner,
            p.x + Math.cos(a) * outer, p.y + Math.sin(a) * outer);
        }
        this.drawShards(ctx, g, p.x, p.y, t, fade, 4);
      } else {
        this.drawShards(ctx, g, p.x, p.y, t, fade, 1.8);
      }
      ctx.restore();
    },

    /** 碎片四散: 带一点重力, 让它们往下掉而不是平着飞 */
    drawShards(ctx, g, cx, cy, t, fade, speedScale) {
      const r = g.radius;
      const gravity = 1.6 * t * t;
      for (const s of this._fx.shards) {
        const px = cx + s.vx * r * speedScale * t;
        const py = cy + s.vy * r * speedScale * t + r * gravity;
        const half = r * s.size * (1 - t * 0.5);
        ctx.save();
        ctx.globalAlpha = fade;
        ctx.fillStyle = s.color;
        ctx.translate(px, py);
        ctx.rotate((s.spin * t * Math.PI) / 180);
        ctx.fillRect(-half, -half, half * 2, half * 2);
        ctx.restore();
      }
    },
  },
});

// ====== 画笔小工具 ======

function line(ctx, x1, y1, x2, y2) {
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
}

function circle(ctx, x, y, r) {
  ctx.beginPath();
  ctx.arc(x, y, Math.max(0, r), 0, Math.PI * 2);
  ctx.fill();
}

function strokeCircle(ctx, x, y, r) {
  ctx.beginPath();
  ctx.arc(x, y, Math.max(0, r), 0, Math.PI * 2);
  ctx.stroke();
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}
