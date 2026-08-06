/**
 * 首页 —— 对齐 Android 的 MainMenuActivity。
 *
 * 信息架构照搬 App 的三档主次:
 *   1. 开始对弈 —— 唯一的实心行动色, 直接开局, 用上次的难度与执子
 *   2. 实时支招 —— 独占一行的卡片, 它是唯一"举着手机对着真实棋盘"的功能
 *   3. 复盘 / 拍照识局 —— 并列双列, 本来就是平级
 *   4. 设置 / 关于 / 语言 —— 底部文字按钮, 低频
 *
 * 旧版把 15 格难度网格摆在首页, 首屏一多半被它占掉, 而 App 的首页压根没有
 * 这个东西 —— 难度是在对局界面里随时能改的。这里把它移回对局页。
 */
const pageBehavior = require('../../behaviors/page');
const store = require('../../core/store');
const rating = require('../../core/rating');
const palette = require('../../core/palette');
const i18n = require('../../core/i18n');
const { difficulties } = require('../../generated/product-config');

Page({
  behaviors: [pageBehavior],

  titleKey: 'app_name',
  textKeys: [
    'app_name', 'menu_play', 'menu_replay', 'menu_recognize', 'menu_settings',
    'menu_about', 'menu_language', 'live_entry', 'menu_live_desc', 'rating_current',
    'mp_feature_unavailable',
  ],

  data: {
    nickname: '',
    rankName: '',
    rankColor: '',
    wins: 0,
    losses: 0,
    draws: 0,
    /** 胜率条三段的 flex 权重 */
    winWeight: 0,
    lossWeight: 0,
    drawWeight: 0,
    gamesText: '',
    playText: '',
  },

  onShow() {
    this.syncChrome();
    this.refreshProfile();
  },

  refreshProfile() {
    const profile = store.getProfile();
    const theme = getApp().globalData.theme;
    const rank = rating.rankOf(profile.rating);
    const setup = store.getLastSetup();
    const difficulty = difficulties[setup.difficulty - 1] || difficulties[3];

    this.setData({
      nickname: profile.nickname || i18n.t('default_nickname'),
      rankName: i18n.t(rank.key),
      rankColor: palette.tone(theme, rank.tone),
      wins: profile.wins,
      losses: profile.losses,
      draws: profile.draws,
      // 一局都没下时三段权重全给 0, 条子塌成一条空胶囊 —— 那正是"还没有
      // 数据"该有的样子, 所以这里不做兜底
      winWeight: profile.wins,
      lossWeight: profile.losses,
      drawWeight: profile.draws,
      gamesText: i18n.t('stat_total_games_format', profile.totalGames),
      winsText: i18n.t('stat_wins_format', profile.wins),
      lossesText: i18n.t('stat_losses_format', profile.losses),
      drawsText: i18n.t('stat_draws_format', profile.draws),
      playText: `${i18n.t('menu_play')} · Lv.${difficulty.level} ${difficulty.name}`,
    });
  },

  /** 直接开局: 难度和执子都能在对局界面里随时改, 不用前置对话框挡两次点击 */
  startGame() {
    const setup = store.getLastSetup();
    wx.navigateTo({
      url: `/pages/game/game?level=${setup.difficulty}&side=${setup.playerSide}`,
    });
  },

  openReplay() {
    wx.navigateTo({ url: '/pages/replay/replay' });
  },

  openSettings() {
    wx.navigateTo({ url: '/pages/settings/settings' });
  },

  /**
   * 拍照识局 / 实时支招在小程序端还没有落地: 两者都要跑 ONNX 推理, 得先把
   * 模型迁到云函数。features.json 里给了 roadmap, 设置页的能力矩阵能查到
   * 具体状态 —— 这里不拿一个点了没反应的假入口糊弄。
   */
  appOnly() {
    wx.showToast({ title: i18n.t('mp_feature_unavailable'), icon: 'none' });
  },

  showAbout() {
    wx.showModal({
      title: i18n.t('menu_about'),
      content: i18n.t('settings_about_text'),
      showCancel: false,
      confirmText: i18n.t('dialog_ok'),
    });
  },

  /**
   * 中英文切换。按钮文字始终显示"切过去会变成的语言", 不是当前语言 ——
   * 所以中英两份 menu_language 各自写死了对方的名字, 这里不用改按钮文案。
   */
  toggleLanguage() {
    i18n.toggleLocale();
    getApp().globalData.locale = i18n.getLocale();
    this.syncChrome();
    this.refreshProfile();
  },
});
