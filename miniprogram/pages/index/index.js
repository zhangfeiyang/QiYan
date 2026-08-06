const { difficulties, features, engineProfile, levelNotes } = require('../../generated/product-config');
const maxLocalLevel = engineProfile.miniprogramMaxLevel;
const localDifficulties = difficulties.slice(0, maxLocalLevel);
Page({
  data: {
    profile: { games: 0, wins: 0, losses: 0, rating: 1200 },
    difficulty: localDifficulties[Math.min(4, localDifficulties.length - 1)],
    difficulties,
    localDifficulties,
    levelNotes,
    maxLocalLevel,
    available: features.features.filter((item) => item.miniprogram).length,
    total: features.features.length,
  },
  onShow() {
    const stored = Number(wx.getStorageSync('difficulty')) || 5;
    const safe = Math.min(maxLocalLevel, Math.max(1, stored));
    this.setData({
      profile: wx.getStorageSync('profile') || this.data.profile,
      difficulty: difficulties[safe - 1],
    });
  },
  startGame() {
    if (this.data.difficulty.level > maxLocalLevel) {
      wx.showToast({ title: '该档位请在 App 端开局', icon: 'none' });
      return;
    }
    wx.navigateTo({ url: `/pages/game/game?level=${this.data.difficulty.level}` });
  },
  pickLevel(event) {
    const level = Number(event.currentTarget.dataset.level);
    if (level > maxLocalLevel) {
      wx.showToast({ title: `Lv.${level} 需在 App 端体验云端引擎`, icon: 'none' });
      return;
    }
    wx.setStorageSync('difficulty', level);
    this.setData({ difficulty: difficulties[level - 1] });
  },
  replay() { wx.navigateTo({ url: '/pages/replay/replay' }); },
  settings() { wx.navigateTo({ url: '/pages/settings/settings' }); },
  pending() { wx.showToast({ title: '平台能力迁移中', icon: 'none' }); },
});
