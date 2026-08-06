const store = require('./core/store');
const i18n = require('./core/i18n');

/**
 * 主题解析。
 *
 * settings.theme 三档与 Android SettingsActivity 的单选组同义:
 *   0 跟随系统 / 1 深色(默认) / 2 浅色
 * 深色是主设计, 所以系统给不出明暗时也落到深色, 而不是浅色。
 */
function resolveTheme(mode) {
  if (mode === store.THEME.LIGHT) return 'light';
  if (mode === store.THEME.DARK) return 'dark';
  const info = wx.getSystemInfoSync();
  return info.theme === 'light' ? 'light' : 'dark';
}

/** 导航栏跟着页面底色走, 不再用一条深褐色横杠把屏幕切两半 */
const NAV_BAR = {
  dark: { backgroundColor: '#262421', frontColor: '#ffffff' },
  light: { backgroundColor: '#F4F2EE', frontColor: '#000000' },
};

App({
  globalData: {
    theme: 'dark',
    locale: 'zh',
  },

  onLaunch() {
    this.globalData.locale = i18n.init();
    this.refreshTheme();
    // 系统切换深浅色时, "跟随系统"的用户要立刻跟上
    wx.onThemeChange(() => {
      if (store.getSettings().theme === store.THEME.SYSTEM) this.refreshTheme();
    });
  },

  /** 重新解析主题并同步导航栏, 返回 'dark' | 'light' */
  refreshTheme() {
    const theme = resolveTheme(store.getSettings().theme);
    this.globalData.theme = theme;
    wx.setNavigationBarColor(NAV_BAR[theme]);
    return theme;
  },
});
