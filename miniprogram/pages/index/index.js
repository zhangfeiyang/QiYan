const { difficulties, features } = require('../../generated/product-config');
Page({
  data: { profile:{games:0,wins:0,losses:0,rating:1200}, difficulty:difficulties[4], available:features.features.filter((item)=>item.miniprogram).length, total:features.features.length },
  onShow() { this.setData({ profile:wx.getStorageSync('profile')||this.data.profile, difficulty:difficulties[(wx.getStorageSync('difficulty')||5)-1] }); },
  startGame() { wx.navigateTo({url:`/pages/game/game?level=${this.data.difficulty.level}`}); },
  openDifficulty() { wx.showActionSheet({itemList:difficulties.map((item)=>`${item.level}. ${item.name} · ${item.rank}`),success:({tapIndex})=>{const difficulty=difficulties[tapIndex];wx.setStorageSync('difficulty',difficulty.level);this.setData({difficulty});}}); },
  replay() { wx.navigateTo({url:'/pages/replay/replay'}); },
  settings() { wx.navigateTo({url:'/pages/settings/settings'}); },
  pending() { wx.showToast({title:'平台能力迁移中',icon:'none'}); }
});
