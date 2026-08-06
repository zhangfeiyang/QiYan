App({
  globalData: {
    advisorApiBaseUrl: '',
    engineApiBaseUrl: ''
  },
  onLaunch() {
    this.globalData.advisorApiBaseUrl = wx.getStorageSync('advisorApiBaseUrl') || '';
    this.globalData.engineApiBaseUrl = wx.getStorageSync('engineApiBaseUrl') || '';
  }
});
