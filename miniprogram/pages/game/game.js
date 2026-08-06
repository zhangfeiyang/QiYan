/**
 * 对局 —— 对齐 Android 的 GameActivity + GameViewModel。
 *
 * 布局与 App 一致, 自上而下:
 *   对手行 (执子点 / 名称 / 档位 chip / 思考中胶囊) → 胜率条 → 棋盘
 *   → 我方行 (执子点 / 段位 / 第 N 手) → 工具条
 *
 * 几处必须照搬的行为, 否则两端玩起来是两个游戏:
 *  - 轮到谁走, 谁那一行就亮起来 (另一行压到 0.45 透明)
 *  - 「翻转」是**真换边**: 我方红黑对调, 引擎接手原来我这边, 视角跟着走
 *  - 被将军时走非应招的着法直接拦下, 不落子
 *  - 档位 chip 整行可点, 对局中随时能改难度, 不重开局
 *  - 认输和悔棋都要二次确认; 认输在工具条最右且用危险色
 */
const pageBehavior = require('../../behaviors/page');
const game = require('../../core/xiangqi');
const notation = require('../../core/notation');
const store = require('../../core/store');
const ratingSystem = require('../../core/rating');
const feedback = require('../../core/feedback');
const i18n = require('../../core/i18n');
const { difficulties, engineProfile } = require('../../generated/product-config');

const MAX_LOCAL_LEVEL = engineProfile.miniprogramMaxLevel;
/** 引擎"思考"的最短间隔: 端侧搜索太快时立刻落子会让人以为是随手棋 */
const ENGINE_MIN_DELAY = 350;
/** AI 分析动效至少跑这么久, 看起来"真的分析了", 再弹总结 (同 GameActivity 的 3400ms) */
const ANALYSIS_SCAN_MS = 3400;
/** 非当前走子方那一行的淡出程度。太淡会看不清对手信息, 0.45 刚好 */
const DIMMED = 0.45;

Page({
  behaviors: [pageBehavior],

  titleKey: 'title_game',
  textKeys: [
    'title_game', 'action_hint', 'action_undo', 'action_flip', 'action_resign',
    'advisor_button', 'status_thinking', 'advisor_send', 'advisor_input_hint',
    'advisor_suggest_analyze', 'advisor_suggest_next', 'advisor_suggest_review',
    'advisor_title', 'advisor_thinking', 'analysis_tap_to_skip',
    'mp_scanning_title', 'mp_scanning_sub', 'mp_analysis_panel_sub',
  ],

  data: {
    board: [],
    sideToMove: game.RED,
    playerSide: game.RED,
    difficulty: null,
    difficultyLabel: '',

    selected: null,
    targets: [],
    lastMove: null,
    checkedKing: null,
    hintMove: null,

    isThinking: false,
    isGameOver: false,
    moveCount: 0,
    moveCountText: '',

    opponentName: '',
    opponentLevelName: '',
    playerName: '',
    playerRank: '',

    redWinRate: 50,
    showWinRate: true,
    showCoords: true,
    animation: true,
    flipped: false,

    playerRowAlpha: 1,
    opponentRowAlpha: DIMMED,

    /** Lv.10 以上端侧跑不动, 进来直接给一张说明卡而不是一盘下不动的棋 */
    appOnly: false,
    appOnlyTitle: '',
    appOnlyBody: '',

    pickerOpen: false,
    analysisScanning: false,
    analysisOpen: false,
    messages: [],
    quickPrompts: [],
    inputText: '',
    sending: false,
  },

  // ====== 生命周期 ======

  onLoad(options) {
    const level = clamp(Number(options.level) || 4, 1, 15);
    const playerSide = Number(options.side) === game.BLACK ? game.BLACK : game.RED;
    this.startNewGame(level, playerSide, options.fen);
  },

  onShow() {
    this.syncChrome();
    const settings = store.getSettings();
    this.setData({
      showWinRate: settings.showWinRate,
      showCoords: settings.showCoords,
      animation: settings.animation,
      quickPrompts: [
        i18n.t('advisor_suggest_analyze'),
        i18n.t('advisor_suggest_next'),
        i18n.t('advisor_suggest_review'),
      ],
    });
    this.applyFlip();
    feedback.bgmResume();
    // 引擎思考和玩家看棋期间不要熄屏
    wx.setKeepScreenOn({ keepScreenOn: true });
  },

  onHide() {
    feedback.bgmPause();
    wx.setKeepScreenOn({ keepScreenOn: false });
  },

  onUnload() {
    feedback.bgmStop();
    wx.setKeepScreenOn({ keepScreenOn: false });
  },

  // ====== 开局 ======

  startNewGame(level, playerSide, startFen) {
    const parsed = startFen ? game.parseFen(decodeURIComponent(startFen)) : null;
    const board = parsed ? parsed.board : game.cloneBoard(game.INITIAL_BOARD);
    const sideToMove = parsed ? parsed.side : game.RED;
    const difficulty = difficulties[level - 1];

    // 悔棋栈与重复局面历史。栈里存的是"走这一步之前"的完整快照 ——
    // 端侧棋盘就是个 10×9 数组, 存快照比实现 undoMove 的逆操作省事得多,
    // 也不会因为逆操作写漏一个字段而把局面弄脏。
    this._history = [];
    this._historyKeys = [game.positionKey(board, sideToMove)];
    this._halfmoveClock = parsed ? parsed.halfmoveClock : 0;
    this._initialFen = game.toFen(board, sideToMove);
    this._moves = [];
    this._startTime = Date.now();
    this._startRating = store.displayedRating(store.getProfile());

    this.setData({
      board,
      sideToMove,
      playerSide,
      difficulty,
      isThinking: false,
      isGameOver: false,
      selected: null,
      targets: [],
      lastMove: null,
      hintMove: null,
      checkedKing: null,
      moveCount: 0,
      redWinRate: 50,
      appOnly: level > MAX_LOCAL_LEVEL,
      appOnlyTitle: i18n.t('mp_app_only_title', `Lv.${level} ${difficulty.name}`),
      appOnlyBody: i18n.t('mp_app_only_body', MAX_LOCAL_LEVEL),
    });

    store.saveLastSetup({ difficulty: level, playerSide });
    this.refreshLabels();
    this.applyFlip();

    // 玩家执黑时引擎先走
    if (sideToMove !== playerSide && level <= MAX_LOCAL_LEVEL) this.makeEngineMove();
  },

  /** 执黑时默认翻到黑方视角, 设置里的"默认翻转"在此基础上再取反 */
  applyFlip() {
    const defaultFlip = this.data.playerSide === game.BLACK;
    this.setData({ flipped: defaultFlip !== store.getSettings().flipped });
  },

  refreshLabels() {
    const { playerSide, difficulty, sideToMove, isGameOver, moveCount } = this.data;
    const profile = store.getProfile();
    const rank = ratingSystem.rankOf(profile.rating);
    const playerRed = playerSide === game.RED;
    const myTurn = sideToMove === playerSide && !isGameOver;

    this.setData({
      opponentName: i18n.t(playerRed ? 'game_opponent_black' : 'game_opponent_red'),
      // 显示档位名而不是分数: 那个 Elo 是估计值, 摆出来是给用户一个并不存在的精确度
      opponentLevelName: difficulty.name,
      difficultyLabel: `Lv.${difficulty.level} ${difficulty.name}`,
      playerName: i18n.t(playerRed ? 'game_player_red' : 'game_player_black'),
      playerRank: i18n.t(rank.key),
      moveCountText: i18n.t('game_move_count', Math.floor(moveCount / 2) + 1),
      playerRowAlpha: myTurn ? 1 : DIMMED,
      opponentRowAlpha: myTurn ? DIMMED : 1,
    });
  },

  boardComponent() {
    return this.selectComponent('#board');
  },

  // ====== 落子 ======

  onCellTap(event) {
    const { row, col } = event.detail;
    const { board, selected, sideToMove, playerSide, isThinking, isGameOver, analysisScanning } = this.data;
    if (isGameOver || isThinking || analysisScanning || this.data.appOnly) return;
    if (sideToMove !== playerSide) return;

    if (selected) {
      const move = game.legalMoves(board, sideToMove, selected)
        .find((item) => item.to.row === row && item.to.col === col);
      if (move) {
        this.playerMove(move);
        return;
      }
    }
    if (game.colorOf(board[row][col]) === sideToMove) {
      const moves = game.legalMoves(board, sideToMove, { row, col });
      this.setData({ selected: { row, col }, targets: moves.map((item) => item.to) });
    } else {
      this.setData({ selected: null, targets: [] });
    }
  },

  /**
   * 玩家落子。
   *
   * 这里不再单独拦"被将军却不应招": legalMoves 生成时就把走完仍被将的着法
   * 滤掉了 (白脸将同理), 所以能走到这儿的着法一定是合法的。Android 那边要
   * 额外截一道, 是因为它的 isLegalMove 走的是另一条更宽的判定路径。
   */
  playerMove(move) {
    this.commitMove(move);
    if (!this.data.isGameOver) this.makeEngineMove();
  },

  /** 真正把一步走到棋盘上, 并推进所有派生状态 */
  commitMove(move) {
    const { board, sideToMove } = this.data;
    const piece = board[move.from.row][move.from.col];
    const captured = board[move.to.row][move.to.col];

    this._history.push({
      board: game.cloneBoard(board),
      sideToMove,
      halfmoveClock: this._halfmoveClock,
      historyKeys: this._historyKeys.slice(),
      lastMove: this.data.lastMove,
    });
    this._moves.push({ uci: game.moveToUci(move), text: notation.toChinese(move, board) });

    const nextBoard = game.applyMove(board, move);
    const nextSide = game.otherSide(sideToMove);
    this._halfmoveClock = captured ? 0 : this._halfmoveClock + 1;
    this._historyKeys.push(game.positionKey(nextBoard, nextSide));

    this.setData({
      board: nextBoard,
      sideToMove: nextSide,
      selected: null,
      targets: [],
      hintMove: null,
      lastMove: { from: move.from, to: move.to },
      moveCount: this._moves.length,
    });

    const boardComponent = this.boardComponent();
    if (boardComponent) {
      boardComponent.playMove(move, piece);
      if (captured) boardComponent.playCaptureEffect(move.to, captured);
    }

    this.updateAfterMove(captured);
  },

  updateAfterMove(captured) {
    const { board, sideToMove } = this.data;
    const inCheck = game.isInCheck(board, sideToMove);
    const over = game.gameOver(board, sideToMove, this._historyKeys, this._halfmoveClock);

    this.setData({
      checkedKing: inCheck ? game.findKing(board, sideToMove) : null,
      redWinRate: game.redWinRate(board),
    });
    this.refreshLabels();

    if (over.type === game.GAME_OVER.CHECKMATE) feedback.onCheckmate();
    else if (inCheck) feedback.onCheck();
    else feedback.onMove(!!captured);

    if (captured) {
      wx.showToast({ title: i18n.t('captured_piece_format', game.CHARS[captured]), icon: 'none', duration: 900 });
    }

    if (over.type !== game.GAME_OVER.NONE) this.endGame(over);
  },

  // ====== 引擎 ======

  makeEngineMove() {
    if (this.data.isGameOver || this.data.appOnly) return;
    this.setData({ isThinking: true });
    setTimeout(() => this.requestEngineMove(), ENGINE_MIN_DELAY);
  },

  requestEngineMove() {
    const { engineApiBaseUrl } = store.getSettings();
    if (!engineApiBaseUrl) {
      this.playEngineLocal();
      return;
    }
    // 云端 Pikafish: 与 App 端同一套 1-15 级映射。没响应/给了非法着法就
    // 退回端侧引擎 —— 宁可这一手弱一点, 也不要让棋局卡在那儿等一个不来的回复。
    wx.request({
      url: `${engineApiBaseUrl}/bestmove`,
      method: 'POST',
      timeout: 15000,
      data: {
        fen: game.toFen(this.data.board, this.data.sideToMove, this._halfmoveClock),
        level: this.data.difficulty.level,
      },
      success: ({ data }) => {
        const move = this.matchEngineMove(data);
        if (move) this.applyEngineMove(move);
        else this.playEngineLocal(true);
      },
      fail: () => this.playEngineLocal(true),
    });
  },

  /** 云端可能回 UCI 串, 也可能回坐标对象; 两种都认, 但必须落在合法着法集里 */
  matchEngineMove(data) {
    if (!data) return null;
    const legal = game.legalMoves(this.data.board, this.data.sideToMove);
    if (typeof data.bestmove === 'string') {
      const parsed = game.uciToMove(data.bestmove, this.data.board);
      if (!parsed) return null;
      return legal.find((item) => same(item.from, parsed.from) && same(item.to, parsed.to)) || null;
    }
    if (data.from && data.to) {
      return legal.find((item) => same(item.from, data.from) && same(item.to, data.to)) || null;
    }
    return null;
  },

  playEngineLocal(fromFallback) {
    if (this.data.isGameOver) return;
    if (fromFallback) wx.showToast({ title: i18n.t('mp_engine_fallback'), icon: 'none' });
    const level = Math.min(MAX_LOCAL_LEVEL, this.data.difficulty.level);
    const move = game.localBestMove(this.data.board, this.data.sideToMove, level);
    if (move) this.applyEngineMove(move);
    else this.setData({ isThinking: false });
  },

  applyEngineMove(move) {
    this.setData({ isThinking: false });
    this.commitMove(move);
  },

  // ====== 工具条 ======

  onHint() {
    const { isThinking, isGameOver, board, sideToMove, playerSide } = this.data;
    if (isThinking || isGameOver || sideToMove !== playerSide) return;
    const level = Math.min(MAX_LOCAL_LEVEL, this.data.difficulty.level);
    const move = game.localBestMove(board, sideToMove, level);
    if (!move) {
      wx.showToast({ title: i18n.t('mp_hint_none'), icon: 'none' });
      return;
    }
    // 只在棋盘上画箭头, 不弹"推荐 X / 备选 Y / 评估 Z / 深度 N"那张卡片:
    // 用户按提示是想知道"走哪儿", 箭头已经把这件事说完了。
    this.setData({ hintMove: { from: move.from, to: move.to }, selected: null, targets: [] });
  },

  onUndo() {
    if (this.data.isThinking) return;
    wx.showModal({
      title: i18n.t('dialog_undo_title'),
      content: i18n.t('dialog_undo_message'),
      confirmText: i18n.t('dialog_yes'),
      cancelText: i18n.t('dialog_no'),
      success: ({ confirm }) => {
        if (confirm) this.undoMove();
      },
    });
  },

  /**
   * 悔棋: 退回到轮玩家走的局面。
   * 轮到玩家 ⇒ 引擎刚走完, 要退引擎那步和玩家上一步(2 步);
   * 轮到引擎 ⇒ 玩家刚走完, 只退玩家这一步(1 步)。
   */
  undoMove() {
    if (!this._history.length) {
      wx.showToast({ title: i18n.t('mp_no_undo'), icon: 'none' });
      return;
    }
    const playerToMove = this.data.sideToMove === this.data.playerSide;
    const steps = Math.min(playerToMove && this._history.length >= 2 ? 2 : 1, this._history.length);
    let snapshot = null;
    for (let i = 0; i < steps; i += 1) {
      snapshot = this._history.pop();
      this._moves.pop();
    }
    this._halfmoveClock = snapshot.halfmoveClock;
    this._historyKeys = snapshot.historyKeys;

    this.setData({
      board: snapshot.board,
      sideToMove: snapshot.sideToMove,
      lastMove: snapshot.lastMove,
      selected: null,
      targets: [],
      hintMove: null,
      isThinking: false,
      isGameOver: false,
      moveCount: this._moves.length,
      redWinRate: 50,
      checkedKing: game.isInCheck(snapshot.board, snapshot.sideToMove)
        ? game.findKing(snapshot.board, snapshot.sideToMove)
        : null,
    });
    this.refreshLabels();
  },

  /**
   * 「翻转」真的换边: 我方红黑对调, 引擎接手原来我这边, 视角跟着走 ——
   * 否则换完自己的子跑到对面去了, 更难认。换完如果轮到的是引擎那一方,
   * 立刻让引擎接着走, 否则局面会卡住等一个永远不会落子的人。
   */
  onFlip() {
    if (this.data.isGameOver) return;
    const playerSide = game.otherSide(this.data.playerSide);
    this.setData({ playerSide, isThinking: false, hintMove: null, selected: null, targets: [] });
    store.saveLastSetup({ playerSide });
    this.applyFlip();
    this.refreshLabels();
    wx.showToast({
      title: i18n.t(playerSide === game.BLACK ? 'game_switched_to_black' : 'game_switched_to_red'),
      icon: 'none',
    });
    if (this.data.sideToMove !== playerSide) this.makeEngineMove();
  },

  onResign() {
    if (this.data.isGameOver) return;
    wx.showModal({
      title: i18n.t('dialog_resign_title'),
      content: i18n.t('dialog_resign_message'),
      confirmText: i18n.t('dialog_yes'),
      cancelText: i18n.t('dialog_no'),
      success: ({ confirm }) => {
        if (confirm) {
          // 认输方永远是玩家自己, 与执红执黑无关
          this.endGame({ type: game.GAME_OVER.NONE, winner: game.otherSide(this.data.playerSide) },
            i18n.t('status_resign'));
        }
      },
    });
  },

  // ====== 难度 ======

  openPicker() {
    if (this.data.isGameOver) return;
    this.setData({ pickerOpen: true });
  },

  closePicker() {
    this.setData({ pickerOpen: false });
  },

  /**
   * 对局中途改 AI 等级, 立即生效, 不重开局: 用户想在被虐时调低、觉得太
   * easy 时调高, 重开就等于让这个功能没意义。引擎参数是每次现算的, 所以
   * 改掉 difficulty 下一手就是新等级; 唯一要处理的是它正在思考 —— 打断重下。
   */
  onPickLevel(event) {
    const level = event.detail.level;
    const difficulty = difficulties[level - 1];
    const wasThinking = this.data.isThinking;
    this.setData({ pickerOpen: false, difficulty, isThinking: false });
    store.saveLastSetup({ difficulty: level });
    this.refreshLabels();
    if (wasThinking && !this.data.isGameOver) this.makeEngineMove();
  },

  // ====== 终局 ======

  endGame(over, reasonOverride) {
    if (this.data.isGameOver) return;
    const { playerSide, difficulty } = this.data;

    let reason = reasonOverride;
    if (!reason) {
      if (over.type === game.GAME_OVER.CHECKMATE) {
        // 走不动的一方输。CHECKMATE 里 winner 已经是获胜方, 直接按它取文案,
        // 不像 Android 那边靠 sideToMove 反推 —— 那段的红黑分支是写反的。
        reason = i18n.t(over.winner === game.BLACK ? 'game_over_checkmate_red' : 'game_over_checkmate_black');
      } else if (over.type === game.GAME_OVER.STALEMATE) reason = i18n.t('status_stalemate');
      else if (over.type === game.GAME_OVER.REPETITION) reason = i18n.t('game_over_repetition');
      else reason = i18n.t('game_over_fifty_moves');
    }

    let result;
    if (over.winner === -1) result = ratingSystem.RESULT.DRAW;
    else result = over.winner === playerSide ? ratingSystem.RESULT.WIN : ratingSystem.RESULT.LOSS;

    // 用档位的实际棋力算分, 与 App 的 engineManager.effectiveElo() 同义
    const profile = store.recordResult(result, difficulty.elo);
    const newRating = store.displayedRating(profile);
    const delta = newRating - this._startRating;
    const rank = ratingSystem.rankOf(profile.rating);

    store.addRecord({
      startTime: this._startTime,
      endTime: Date.now(),
      playerSide,
      difficulty: difficulty.level,
      engineElo: difficulty.elo,
      result: result === ratingSystem.RESULT.WIN ? 1 : result === ratingSystem.RESULT.LOSS ? -1 : 0,
      ratingDelta: delta,
      playerRating: newRating,
      moves: this._moves,
      initialFen: this._initialFen,
      endReason: reason,
    });

    this.setData({ isGameOver: true, isThinking: false, selected: null, targets: [] });
    this.refreshLabels();

    wx.showModal({
      title: i18n.t('game_over_title'),
      content: i18n.t('game_over_body', reason, (delta >= 0 ? '+' : '') + delta, i18n.t(rank.key)),
      confirmText: i18n.t('action_new_game'),
      cancelText: i18n.t('action_back'),
      success: ({ confirm }) => {
        if (confirm) this.startNewGame(difficulty.level, playerSide);
        else wx.navigateBack();
      },
    });
  },

  // ====== AI 分析 ======

  /**
   * 点「AI 分析」: 先铺全屏扫描动效, 扫几秒后淡出, 再弹出对局面的总结,
   * 之后可以继续追问。抽屉是半透明的, 棋盘一直可见 —— 用户是在"看着这盘棋
   * 问问题", 不是切到另一个界面。
   */
  openAnalysis() {
    if (this.data.isGameOver || this.data.analysisScanning || this.data.analysisOpen) return;
    this.setData({ analysisScanning: true });
    setTimeout(() => this.finishScan(), ANALYSIS_SCAN_MS);
  },

  /** 轻触跳过动效, 直接进对话 */
  skipScan() {
    if (this.data.analysisScanning) this.finishScan();
  },

  finishScan() {
    if (!this.data.analysisScanning) return;
    const level = Math.min(MAX_LOCAL_LEVEL, this.data.difficulty.level);
    const suggestion = game.localBestMove(this.data.board, this.data.sideToMove, level);
    const candidate = suggestion
      ? i18n.t('mp_analysis_candidate', notation.toChinese(suggestion, this.data.board))
      : '';
    this.setData({
      analysisScanning: false,
      analysisOpen: true,
      messages: [{ role: 'assistant', text: i18n.t('mp_analysis_summary', candidate) }],
    });
  },

  closeAnalysis() {
    if (!this.data.sending) this.setData({ analysisOpen: false });
  },

  onInput(event) {
    this.setData({ inputText: event.detail.value });
  },

  usePrompt(event) {
    this.setData({ inputText: event.currentTarget.dataset.text });
    this.sendMessage();
  },

  sendMessage() {
    const text = this.data.inputText.trim();
    if (!text || this.data.sending) return;
    const messages = this.data.messages.concat([{ role: 'user', text }]);
    this.setData({ messages, inputText: '', sending: true });

    const { advisorApiBaseUrl } = store.getSettings();
    const reply = (answer) => this.setData({
      messages: this.data.messages.concat([{ role: 'assistant', text: answer }]),
      sending: false,
    });

    if (!advisorApiBaseUrl) {
      setTimeout(() => reply(i18n.t('mp_advisor_local_reply')), 500);
      return;
    }
    wx.request({
      url: `${advisorApiBaseUrl}/analyze`,
      method: 'POST',
      timeout: 30000,
      data: {
        fen: game.toFen(this.data.board, this.data.sideToMove, this._halfmoveClock),
        message: text,
        history: messages,
      },
      success: ({ data }) => reply((data && (data.text || data.content)) || i18n.t('advisor_error_generic')),
      fail: () => reply(i18n.t('advisor_unavailable')),
    });
  },
});

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function same(a, b) {
  return a && b && a.row === b.row && a.col === b.col;
}
