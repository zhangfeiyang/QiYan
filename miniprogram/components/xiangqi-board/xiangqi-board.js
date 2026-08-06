const { CHARS, colorOf } = require('../../core/xiangqi');
Component({
  properties: { board:Array, selected:Object, targets:Array, flipped:Boolean, disabled:Boolean },
  data: { rows:[], topFiles:[], bottomFiles:[] },
  observers: { 'board,selected,targets,flipped': function() { this.rebuild(); } },
  methods: {
    rebuild() {
      const board=this.properties.board||[]; const flipped=this.properties.flipped; const selected=this.properties.selected||{}; const targets=this.properties.targets||[];
      const rows=[];
      for(let displayRow=0;displayRow<10;displayRow++) {
        const row=flipped?9-displayRow:displayRow; const cells=[];
        for(let displayCol=0;displayCol<9;displayCol++) { const col=flipped?8-displayCol:displayCol; const piece=board[row]?.[col]||''; cells.push({row,col,piece,char:CHARS[piece]||'',red:colorOf(piece)===1,selected:selected.row===row&&selected.col===col,target:targets.some((item)=>item.row===row&&item.col===col)}); }
        rows.push({key:row,cells});
      }
      const files=(flipped?[9,8,7,6,5,4,3,2,1]:[1,2,3,4,5,6,7,8,9]);
      this.setData({rows,topFiles:files,bottomFiles:files});
    },
    tapCell(event) { if(!this.properties.disabled) this.triggerEvent('celltap',{row:Number(event.currentTarget.dataset.row),col:Number(event.currentTarget.dataset.col)}); }
  }
});
