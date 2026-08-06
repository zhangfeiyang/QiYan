const { cloneBoard, INITIAL_BOARD } = require('../../core/xiangqi');
Page({
  data:{board:cloneBoard(INITIAL_BOARD),moves:[],index:0,status:'暂无棋谱',flipped:false},
  onLoad(){const record=wx.getStorageSync('lastGame');if(!record||!record.history?.length)return;const snapshots=record.history.map((item)=>item.board).concat([record.board]);this.snapshots=snapshots;this.setData({board:snapshots[0],moves:record.history.map((item)=>item.label),status:record.status||'最近一局',index:0});},
  previous(){if(!this.snapshots)return;const index=Math.max(0,this.data.index-1);this.setData({index,board:this.snapshots[index]});},
  next(){if(!this.snapshots)return;const index=Math.min(this.snapshots.length-1,this.data.index+1);this.setData({index,board:this.snapshots[index]});},
  flip(){this.setData({flipped:!this.data.flipped});}
});
