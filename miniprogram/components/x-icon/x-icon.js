/**
 * 图标 —— 直接渲染 Android drawable/ic_*.xml 里的 pathData。
 *
 * 路径由 tools/sync-shared-config.mjs 抽到 generated/icons.js, 两端用的是
 * 同一组图形; 换成 emoji 或者另找一套图标包, 小程序看起来就是另一个产品了。
 *
 * 用 background-image + data URI 而不是 <image src>: 后者要走一次网络/文件
 * 解码, 而这些图标只有几十字节, 内联进样式最快, 也免去了资源打包。
 */
const ICONS = require('../../generated/icons');
const palette = require('../../core/palette');

Component({
  properties: {
    name: { type: String, value: '' },
    /**
     * 调色板里的 token 名 (textSecondary / action / danger ...)。
     * 用 token 而不是让每个调用点写死色值: 主题一切换, 图标要跟着翻, 写死的
     * 那些就会在浅色底上继续用深色主题的色。
     */
    token: { type: String, value: 'textSecondary' },
    theme: { type: String, value: 'dark' },
    /** 边长, rpx */
    size: { type: Number, value: 48 },
  },

  data: { style: '' },

  observers: {
    'name, token, theme, size'() {
      this.setData({ style: this.buildStyle() });
    },
  },

  lifetimes: {
    attached() {
      this.setData({ style: this.buildStyle() });
    },
  },

  methods: {
    buildStyle() {
      const paths = ICONS[this.data.name];
      if (!paths) return `width:${this.data.size}rpx;height:${this.data.size}rpx;`;
      const colors = palette.get(this.data.theme);
      const color = colors[this.data.token] || colors.textSecondary;
      const body = paths.map((d) => `<path d="${d}" fill="${color}"/>`).join('');
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">${body}</svg>`;
      // '#' 会被当成 URL 片段截断, 必须编码; 其余字符 data URI 能原样吃下
      const uri = encodeURIComponent(svg).replace(/'/g, '%27');
      return `width:${this.data.size}rpx;height:${this.data.size}rpx;` +
        `background-image:url("data:image/svg+xml,${uri}");`;
    },
  },
});
