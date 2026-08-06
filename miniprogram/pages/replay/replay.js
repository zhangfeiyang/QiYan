/**
 * 对局列表 (复盘主页) —— 对齐 Android 的 ReplayActivity。
 *
 * 每行: 胜负 + 执子 + 难度 + 时间 + 手数与结束原因 + 段位与评分变化。
 * 胜负用**语义色**而不是执子色 —— 胜=行动色, 负=危险色, 与首页胜率条的
 * 图例同一套; 用红/黑那两个执子色的话, 赢和输会染成同一族的颜色, 扫一眼
 * 列表根本分不出战绩。
 */
const pageBehavior = require('../../behaviors/page');
const store = require('../../core/store');
const rating = require('../../core/rating');
const i18n = require('../../core/i18n');

Page({
  behaviors: [pageBehavior],

  titleKey: 'title_replay',
  textKeys: ['title_replay', 'replay_empty'],

  data: { records: [] },

  onShow() {
    this.syncChrome();
    this.loadRecords();
  },

  loadRecords() {
    const records = store.getRecords().map((record) => {
      const rank = rating.rankOf(record.playerRating);
      const delta = record.ratingDelta >= 0 ? `+${record.ratingDelta}` : `${record.ratingDelta}`;
      return {
        id: record.id,
        resultText: i18n.t(
          record.result === 1 ? 'result_win_short' : record.result === -1 ? 'result_loss_short' : 'result_draw_short'
        ),
        resultClass: record.result === 1 ? 'win' : record.result === -1 ? 'loss' : 'draw',
        sideText: i18n.t(record.playerSide === 1 ? 'side_red_short' : 'side_black_short'),
        levelText: `Lv.${record.difficulty}`,
        dateText: formatDate(record.startTime),
        summary: i18n.t('replay_moves_summary', record.moves.length, record.endReason),
        ratingText: i18n.t('replay_rank_delta', i18n.t(rank.key), delta),
      };
    });
    this.setData({ records });
  },

  openRecord(event) {
    wx.navigateTo({ url: `/pages/replay-viewer/replay-viewer?id=${event.currentTarget.dataset.id}` });
  },
});

/** MM-dd HH:mm, 与 ReplayActivity 的 SimpleDateFormat 同格式 */
function formatDate(ms) {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
