/**
 * 设置 —— 对齐 Android 的 SettingsActivity。
 *
 * 分区与开关逐项对应, 连副标题都用同一批 settings_desc_* 文案: 那些副标题
 * 存在的理由是"默认翻转"翻转什么、"哈希表"调大调小有什么后果, 用户只能靠猜。
 *
 * 两处与 App 的差异, 都是平台差异而不是功能缺失:
 *  - 线程数 / 哈希表: 那是给原生 Pikafish 的 UCI 选项。端侧 JS 引擎是单线程、
 *    没有置换表, 调了不会有任何效果, 摆一个假滑块比不摆更糟 —— 所以这一档
 *    换成"云端引擎服务地址", 配上之后由服务端按同一套 1-15 级映射来跑。
 *  - 背景音乐同样保留: 曲子和 App 是同一首。
 */
const pageBehavior = require('../../behaviors/page');
const store = require('../../core/store');
const feedback = require('../../core/feedback');
const i18n = require('../../core/i18n');
const { features, fingerprint } = require('../../generated/product-config');

Page({
  behaviors: [pageBehavior],

  titleKey: 'title_settings',
  textKeys: [
    'title_settings', 'settings_section_display', 'settings_section_other',
    'settings_sound', 'settings_desc_sound',
    'settings_vibrate', 'settings_desc_vibrate',
    'settings_bgm', 'settings_desc_bgm',
    'settings_show_coords', 'settings_desc_coords',
    'settings_show_winrate', 'settings_desc_winrate',
    'settings_animation', 'settings_desc_animation',
    'settings_board_flip', 'settings_desc_flip',
    'settings_theme', 'settings_theme_system', 'settings_theme_light', 'settings_theme_dark',
    'settings_clear_data', 'settings_clear_data_desc', 'settings_about',
    'mp_cloud_section', 'mp_cloud_help', 'mp_engine_url', 'mp_advisor_url',
    'mp_save_config', 'mp_feature_matrix',
  ],

  data: {
    settings: {},
    themeOptions: [],
    featureRows: [],
    fingerprintText: '',
  },

  onShow() {
    this.syncChrome();
    this.loadSettings();
  },

  loadSettings() {
    const settings = store.getSettings();
    this.setData({
      settings,
      themeOptions: [
        { value: store.THEME.SYSTEM, label: i18n.t('settings_theme_system') },
        { value: store.THEME.DARK, label: i18n.t('settings_theme_dark') },
        { value: store.THEME.LIGHT, label: i18n.t('settings_theme_light') },
      ],
      featureRows: features.features.map((feature) => ({
        id: feature.id,
        name: feature.name,
        roadmap: feature.roadmap || '',
        ready: feature.miniprogram,
        stateText: i18n.t(feature.miniprogram ? 'mp_synced' : 'mp_pending'),
      })),
      fingerprintText: i18n.t('mp_fingerprint', fingerprint),
    });
  },

  /** 所有开关走同一个处理: data-key 指明改哪一项 */
  onToggle(event) {
    const key = event.currentTarget.dataset.key;
    const settings = store.updateSettings({ [key]: event.detail.value });
    this.setData({ settings });
    // 背景音乐要立刻生效, 不用重进对局
    if (key === 'bgm') {
      if (settings.bgm) feedback.bgmResume();
      else feedback.bgmStop();
    }
  },

  onPickTheme(event) {
    const theme = Number(event.currentTarget.dataset.value);
    this.setData({ settings: store.updateSettings({ theme }) });
    this.syncChrome();
  },

  onEngineUrl(event) {
    this._engineUrl = event.detail.value;
  },

  onAdvisorUrl(event) {
    this._advisorUrl = event.detail.value;
  },

  saveUrls() {
    const patch = {};
    if (this._engineUrl !== undefined) patch.engineApiBaseUrl = this._engineUrl.trim();
    if (this._advisorUrl !== undefined) patch.advisorApiBaseUrl = this._advisorUrl.trim();
    this.setData({ settings: store.updateSettings(patch) });
    wx.showToast({ title: i18n.t('mp_saved'), icon: 'success' });
  },

  showAbout() {
    wx.showModal({
      title: i18n.t('menu_about'),
      content: i18n.t('settings_about_text'),
      showCancel: false,
      confirmText: i18n.t('dialog_ok'),
    });
  },

  clearData() {
    wx.showModal({
      title: i18n.t('dialog_clear_data_title'),
      content: i18n.t('dialog_clear_data_message'),
      confirmText: i18n.t('dialog_yes'),
      cancelText: i18n.t('dialog_no'),
      success: ({ confirm }) => {
        if (!confirm) return;
        store.clearAll();
        this.syncChrome();
        this.loadSettings();
      },
    });
  },
});
