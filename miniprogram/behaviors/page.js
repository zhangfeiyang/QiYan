const i18n = require('../core/i18n');

/**
 * 每个页面都要做的两件事: 挂主题类、把文案灌进 data。
 *
 * 主题走 class 而不是全局 CSS 变量的原因: 小程序没有 :root, page 选择器里
 * 声明的变量在运行时改不了 —— 只能由页面根节点带一个 .theme-light 把浅色
 * 那套覆盖上去。
 *
 * 用法:
 *   const pageBehavior = require('../../behaviors/page');
 *   Page({ behaviors: [pageBehavior], textKeys: ['menu_play'], onShow() { this.syncChrome(); } })
 * 之后在 wxml 里用 {{T.menu_play}}; 带参数的文案在页面里调 this.t()。
 */
module.exports = Behavior({
  data: {
    /** 'dark' | 'light'。canvas 组件和图标要按它取色 */
    theme: 'dark',
    /** 根节点的主题类, 深色时为空串 */
    themeClass: '',
    /** 文案表, key 与 Android strings.xml 相同 */
    T: {},
  },

  methods: {
    /** onShow 里调: 从设置页改完主题/语言返回时也要跟着变 */
    syncChrome() {
      const theme = getApp().refreshTheme();
      const T = {};
      for (const key of this.textKeys || []) T[key] = i18n.t(key);
      this.setData({ theme, themeClass: theme === 'light' ? 'theme-light' : '', T });
      if (this.titleKey) wx.setNavigationBarTitle({ title: i18n.t(this.titleKey) });
    },

    /** 带参数的文案 */
    t(key, ...args) {
      return i18n.t(key, ...args);
    },
  },
});
