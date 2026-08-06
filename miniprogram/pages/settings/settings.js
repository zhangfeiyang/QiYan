const { features, fingerprint } = require('../../generated/product-config');
Page({
  data:{engineUrl:'',advisorUrl:'',features:features.features,fingerprint},
  onLoad(){const app=getApp();this.setData({engineUrl:app.globalData.engineApiBaseUrl||wx.getStorageSync('engineApiBaseUrl')||'',advisorUrl:app.globalData.advisorApiBaseUrl||wx.getStorageSync('advisorApiBaseUrl')||''});},
  engineInput(e){this.setData({engineUrl:e.detail.value});},advisorInput(e){this.setData({advisorUrl:e.detail.value});},
  save(){const app=getApp();const engineUrl=this.data.engineUrl.trim().replace(/\/$/,'');const advisorUrl=this.data.advisorUrl.trim().replace(/\/$/,'');app.globalData.engineApiBaseUrl=engineUrl;app.globalData.advisorApiBaseUrl=advisorUrl;wx.setStorageSync('engineApiBaseUrl',engineUrl);wx.setStorageSync('advisorApiBaseUrl',advisorUrl);wx.showToast({title:'已保存'});}
});
