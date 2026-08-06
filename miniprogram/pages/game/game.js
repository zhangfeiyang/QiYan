const game = require('../../core/xiangqi');
const { difficulties, engineProfile } = require('../../generated/product-config');
const maxLocalLevel = engineProfile.miniprogramMaxLevel;
const QUICK_PROMPTS = ['当前局面谁更占优？','我下一步应注意什么？','有哪些战术机会？'];
Page({
  data: { board:game.cloneBoard(game.INITIAL_BOARD),side:game.RED,selected:null,targets:[],history:[],flipped:false,thinking:false,gameOver:false,status:'红方走棋',difficulty:difficulties[4],maxLocalLevel,appOnlyHint:false,analysisOpen:false,analysisScanning:false,messages:[],quickPrompts:QUICK_PROMPTS,inputText:'',sending:false },
  onLoad(options) { const level=Math.min(15,Math.max(1,Number(options.level)||5)); const difficulty=difficulties[level-1]; this.setData({difficulty,appOnlyHint:difficulty.level>maxLocalLevel,status:difficulty.level>maxLocalLevel?`Lv.${difficulty.level} 仅在 App 端可下` :'红方走棋'}); },
  onCellTap(event) {
    if(this.data.gameOver||this.data.thinking||this.data.analysisScanning) return;
    const {row,col}=event.detail; const {board,selected,side}=this.data; const piece=board[row][col];
    if(selected) { const move=game.legalMoves(board,side,selected).find((item)=>item.to.row===row&&item.to.col===col); if(move){this.playMove(move);return;} }
    if(game.colorOf(piece)===side) { const moves=game.legalMoves(board,side,{row,col}); this.setData({selected:{row,col},targets:moves.map((item)=>item.to)}); } else this.setData({selected:null,targets:[]});
  },
  playMove(move) {
    const label=game.moveLabel(move,this.data.board); const next=game.applyMove(this.data.board,move); const history=this.data.history.concat([{board:game.cloneBoard(this.data.board),side:this.data.side,label}]); const side=this.data.side===game.RED?game.BLACK:game.RED;
    this.setData({board:next,side,history,selected:null,targets:[],status:side===game.RED?'红方走棋':'黑方思考中',thinking:side===game.BLACK});
    this.checkEndOrReply();
  },
  checkEndOrReply() { const moves=game.legalMoves(this.data.board,this.data.side); if(!moves.length){this.finish(this.data.side===game.RED?'黑方胜':'红方胜');return;} if(this.data.side===game.BLACK) setTimeout(()=>this.requestEngineMove(),350); },
  requestEngineMove() {
    const app=getApp(); const base=app.globalData.engineApiBaseUrl; const fallback=()=>{const move=game.localBestMove(this.data.board,game.BLACK,Math.min(maxLocalLevel,this.data.difficulty.level));if(move)this.playMove(move);else this.finish('红方胜');};
    if(!base){fallback();return;}
    wx.request({url:`${base}/bestmove`,method:'POST',data:{fen:game.toFen(this.data.board,this.data.side),level:this.data.difficulty.level},success:({data})=>{const all=game.legalMoves(this.data.board,game.BLACK);const move=all.find((item)=>item.from.row===data.from?.row&&item.from.col===data.from?.col&&item.to.row===data.to?.row&&item.to.col===data.to?.col);move?this.playMove(move):fallback();},fail:fallback});
  },
  hint() { if(this.data.thinking||this.data.gameOver)return; const move=game.localBestMove(this.data.board,this.data.side,Math.min(maxLocalLevel,this.data.difficulty.level)); if(move){this.setData({selected:move.from,targets:[move.to],status:`提示：${game.moveLabel(move,this.data.board)}`});} },
  undo() { const history=this.data.history.slice(); if(!history.length)return wx.showToast({title:'暂无可悔棋步数',icon:'none'}); let previous=history.pop(); if(previous.side===game.BLACK&&history.length)previous=history.pop(); this.setData({board:previous.board,side:previous.side,history,selected:null,targets:[],thinking:false,gameOver:false,status:previous.side===game.RED?'红方走棋':'黑方走棋'}); },
  flip() { this.setData({flipped:!this.data.flipped}); },
  resign() { wx.showModal({title:'确认认输',content:'本局将记录为负局。',success:({confirm})=>{if(confirm)this.finish('你已认输');}}); },
  finish(status) { const profile=wx.getStorageSync('profile')||{games:0,wins:0,losses:0,rating:1200};profile.games++;if(status.includes('红方胜')){profile.wins++;profile.rating+=12;}else{profile.losses++;profile.rating=Math.max(600,profile.rating-8);}wx.setStorageSync('profile',profile);wx.setStorageSync('lastGame',{history:this.data.history,board:this.data.board,status,date:Date.now()});this.setData({gameOver:true,thinking:false,status}); },
  openAnalysis() { this.setData({analysisScanning:true,status:'AI 正在扫描全盘…'});setTimeout(()=>{const suggestion=game.localBestMove(this.data.board,this.data.side,Math.min(maxLocalLevel,this.data.difficulty.level));const text=`已完成当前局面扫描。${suggestion?`候选着法：${game.moveLabel(suggestion,this.data.board)}。`:''}你可以继续询问具体变化。`;this.setData({analysisScanning:false,analysisOpen:true,messages:[{role:'assistant',text}],status:this.data.side===game.RED?'红方走棋':'黑方走棋'});},1800); },
  closeAnalysis() { if(!this.data.sending)this.setData({analysisOpen:false}); },
  usePrompt(event) { const text=event.currentTarget.dataset.text; this.setData({inputText:text}); this.sendMessage(); },
  onInput(event) { this.setData({inputText:event.detail.value}); },
  sendMessage() { const text=this.data.inputText.trim();if(!text||this.data.sending)return;const messages=this.data.messages.concat([{role:'user',text}]);this.setData({messages,inputText:'',sending:true});const app=getApp(),base=app.globalData.advisorApiBaseUrl;const fallback=()=>this.setData({messages:this.data.messages.concat([{role:'assistant',text:'从本地规则看，应优先保证将帅安全、检查对方强制手段，再比较吃子与先手。配置 AI 服务地址后可获得更深入的连续对话分析。'}]),sending:false});if(!base){setTimeout(fallback,500);return;}wx.request({url:`${base}/analyze`,method:'POST',data:{fen:game.toFen(this.data.board,this.data.side),message:text,history:messages},success:({data})=>this.setData({messages:this.data.messages.concat([{role:'assistant',text:data.text||data.content||'分析完成'}]),sending:false}),fail:fallback}); }
});
