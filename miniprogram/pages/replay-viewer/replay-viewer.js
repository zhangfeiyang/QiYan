/**
 * 对局回放 —— 对齐 Android 的 ReplayViewerActivity。
 *
 * 首/上/下/末 + 自动播放, 右侧一列中文记谱可以直接点着跳。
 * 棋谱损坏时停在能走到的最后一步并提示, 而不是继续错位地走下去。
 */
const pageBehavior = require('../../behaviors/page');
const game = require('../../core/xiangqi');
const store = require('../../core/store');
const rating = require('../../core/rating');
const i18n = require('../../core/i18n');

const AUTO_PLAY_INTERVAL_MS = 1000;

Page({
  behaviors: [pageBehavior],

  titleKey: 'replay_viewer_title',
  textKeys: [
    'replay_viewer_title', 'replay_first', 'replay_prev', 'replay_next',
    'replay_last', 'replay_play', 'replay_pause',
  ],

  data: {
    board: [],
    lastMove: null,
    flipped: false,
    showCoords: true,
    titleText: '',
    playerInfo: '',
    moveInfo: '',
    /** 已走的步数 */
    step: 0,
    total: 0,
    moves: [],
    autoPlaying: false,
    autoText: '',
  },

  onLoad(options) {
    const record = store.getRecord(options.id);
    if (!record) {
      wx.showToast({ title: i18n.t('replay_record_missing'), icon: 'none' });
      setTimeout(() => wx.navigateBack(), 800);
      return;
    }
    this._record = record;
    // 回放视角跟着当时的执子走: 执黑那局摆成黑方视角, 才和当时下的时候一样
    this.setData({
      flipped: record.playerSide === game.BLACK,
      showCoords: store.getSettings().showCoords,
      total: record.moves.length,
      moves: record.moves.map((move, index) => ({ index: index + 1, text: move.text })),
    });
    this.resetToStart();
    this.refreshHeader();
    this.gotoStep(0, true);
  },

  onShow() {
    this.syncChrome();
    this.setData({ autoText: i18n.t('replay_play') });
  },

  onHide() {
    // 界面不可见时继续自动播放没有意义
    this.stopAutoPlay();
  },

  onUnload() {
    this.stopAutoPlay();
  },

  refreshHeader() {
    const record = this._record;
    const rank = rating.rankOf(record.playerRating);
    const resultText = i18n.t(
      record.result === 1 ? 'result_win_short' : record.result === -1 ? 'result_loss_short' : 'result_draw_short'
    );
    this.setData({
      titleText: `${formatDate(record.startTime)}  ${resultText}`,
      playerInfo: i18n.t('replay_player_info', record.difficulty, i18n.t(rank.key), record.moves.length),
    });
  },

  /** 回到起始局面(优先用记录里的 FEN, 缺失或损坏时退回标准开局) */
  resetToStart() {
    const parsed = game.parseFen(this._record.initialFen);
    this._board = parsed ? parsed.board : game.cloneBoard(game.INITIAL_BOARD);
    this._side = parsed ? parsed.side : game.RED;
  },

  /**
   * 跳到第 step 手。
   *
   * 从头重放而不是逐步撤销: 端侧没有 undo 栈, 而一局最多百来手, 重放的开销
   * 完全可以忽略, 换来的是"任何时候索引和棋盘都不会脱节"。
   */
  gotoStep(step, force) {
    const total = this.data.total;
    const target = Math.max(0, Math.min(total, step));
    if (!force && target === this.data.step) return;

    this.resetToStart();
    let board = this._board;
    let side = this._side;
    let lastMove = null;
    let applied = 0;

    for (let i = 0; i < target; i += 1) {
      const parsed = game.uciToMove(this._record.moves[i].uci, board);
      const legal = parsed && game.legalMoves(board, side)
        .find((item) => item.from.row === parsed.from.row && item.from.col === parsed.from.col
          && item.to.row === parsed.to.row && item.to.col === parsed.to.col);
      if (!legal) {
        wx.showToast({ title: i18n.t('replay_move_undo_failed', i + 1), icon: 'none' });
        this.stopAutoPlay();
        break;
      }
      board = game.applyMove(board, legal);
      side = game.otherSide(side);
      lastMove = { from: legal.from, to: legal.to };
      applied += 1;
    }

    this.setData({
      board,
      lastMove,
      step: applied,
      moveInfo: i18n.t('replay_move_number', applied),
    });
  },

  first() { this.gotoStep(0); },
  prev() { this.gotoStep(this.data.step - 1); },
  next() { this.gotoStep(this.data.step + 1); },
  last() { this.gotoStep(this.data.total); },

  jumpTo(event) {
    this.stopAutoPlay();
    this.gotoStep(Number(event.currentTarget.dataset.index));
  },

  toggleAutoPlay() {
    if (this.data.autoPlaying) {
      this.stopAutoPlay();
      return;
    }
    if (this.data.step >= this.data.total) return; // 已到末尾, 无需播放
    this.setData({ autoPlaying: true, autoText: i18n.t('replay_pause') });
    const tick = () => {
      if (!this.data.autoPlaying) return;
      if (this.data.step >= this.data.total) {
        this.stopAutoPlay();
        return;
      }
      this.gotoStep(this.data.step + 1);
      if (this.data.autoPlaying) this._timer = setTimeout(tick, AUTO_PLAY_INTERVAL_MS);
    };
    tick();
  },

  stopAutoPlay() {
    if (this._timer) clearTimeout(this._timer);
    this._timer = null;
    if (this.data.autoPlaying) this.setData({ autoPlaying: false, autoText: i18n.t('replay_play') });
  },
});

function formatDate(ms) {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
