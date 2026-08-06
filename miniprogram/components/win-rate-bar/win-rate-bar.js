/**
 * 实时胜率条 —— 对应 Android 的 WinRateBarView.kt。
 *
 * 红色在左、黑色在右, 分隔线的位置就是当前形势; 颜色饱和度随优势程度增强。
 * 数值变化用 300ms 补间, 从"当前画到的位置"接着动, 避免连续更新时来回跳。
 */
const palette = require('../../core/palette');

const ANIMATION_MS = 300;

Component({
  properties: {
    /** 红方胜率 0..100 */
    redWinRate: { type: Number, value: 50 },
    theme: { type: String, value: 'dark' },
  },

  observers: {
    redWinRate(next) {
      if (this._displayed === undefined) {
        this._displayed = next;
        this._from = next;
        this._target = next;
        this.requestDraw();
        return;
      }
      this._from = this._displayed;
      this._target = next;
      this._start = Date.now();
      this.startLoop();
    },
    theme() {
      this.requestDraw();
    },
  },

  lifetimes: {
    attached() {
      this._rafId = 0;
    },
    ready() {
      this.initCanvas();
    },
    detached() {
      if (this._rafId && this._canvas) this._canvas.cancelAnimationFrame(this._rafId);
    },
  },

  methods: {
    initCanvas() {
      wx.createSelectorQuery()
        .in(this)
        .select('#bar')
        .fields({ node: true, size: true })
        .exec((res) => {
          const item = res && res[0];
          if (!item || !item.node) return;
          const canvas = item.node;
          const ctx = canvas.getContext('2d');
          const dpr = wx.getSystemInfoSync().pixelRatio || 2;
          canvas.width = item.width * dpr;
          canvas.height = item.height * dpr;
          ctx.scale(dpr, dpr);
          this._canvas = canvas;
          this._ctx = ctx;
          this._w = item.width;
          this._h = item.height;
          if (this._displayed === undefined) this._displayed = this.data.redWinRate;
          this.draw();
        });
    },

    startLoop() {
      if (this._rafId || !this._canvas) return;
      const tick = () => {
        const p = Math.min(1, (Date.now() - this._start) / ANIMATION_MS);
        this._displayed = this._from + (this._target - this._from) * p;
        this.draw();
        this._rafId = p < 1 ? this._canvas.requestAnimationFrame(tick) : 0;
      };
      this._rafId = this._canvas.requestAnimationFrame(tick);
    },

    requestDraw() {
      if (this._ctx) this.draw();
    },

    draw() {
      const ctx = this._ctx;
      if (!ctx) return;
      const c = palette.get(this.data.theme);
      const w = this._w;
      const h = this._h;
      const redPercent = Math.max(0, Math.min(100, this._displayed));
      const redWidth = (w * redPercent) / 100;

      ctx.clearRect(0, 0, w, h);
      ctx.fillStyle = c.surfaceSunken;
      ctx.fillRect(0, 0, w, h);

      if (redWidth > 0) {
        const intensity = Math.min(1, Math.abs(redPercent - 50) / 50);
        const grad = ctx.createLinearGradient(0, 0, redWidth, 0);
        grad.addColorStop(0, blend(c.winrateRedSoft, c.winrateRedStrong, intensity));
        grad.addColorStop(1, c.winrateRedStrong);
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, redWidth, h);
      }
      if (redWidth < w) {
        const intensity = Math.min(1, Math.abs(50 - redPercent) / 50);
        const grad = ctx.createLinearGradient(redWidth, 0, w, 0);
        grad.addColorStop(0, c.winrateBlackStrong);
        grad.addColorStop(1, blend(c.winrateBlackSoft, c.winrateBlackStrong, intensity));
        ctx.fillStyle = grad;
        ctx.fillRect(redWidth, 0, w - redWidth, h);
      }

      ctx.strokeStyle = c.borderSubtle;
      ctx.lineWidth = 1;
      ctx.strokeRect(0.5, 0.5, w - 1, h - 1);

      ctx.strokeStyle = c.textPrimary;
      ctx.beginPath();
      ctx.moveTo(redWidth, 0);
      ctx.lineTo(redWidth, h);
      ctx.stroke();

      // 两半各自取色, 不能统一用白字: 暗色主题下黑方那半是浅色底, 白字压上去会糊没
      ctx.font = `bold ${Math.min(h * 0.55, 14)}px serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = c.winrateTextOnRed;
      ctx.fillText(`${redPercent.toFixed(1)}%`, redWidth * 0.5, h * 0.55);
      ctx.fillStyle = c.winrateTextOnBlack;
      ctx.fillText(`${(100 - redPercent).toFixed(1)}%`, redWidth + (w - redWidth) * 0.5, h * 0.55);
    },
  },
});

function blend(hex1, hex2, ratio) {
  const r = Math.max(0, Math.min(1, ratio));
  const a = parseHex(hex1);
  const b = parseHex(hex2);
  const mix = (i) => Math.round(a[i] * (1 - r) + b[i] * r);
  return `rgb(${mix(0)}, ${mix(1)}, ${mix(2)})`;
}

function parseHex(hex) {
  const v = parseInt(hex.slice(1), 16);
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
}
