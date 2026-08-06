/**
 * 难度选择 —— 对应 Android 的 dialog_difficulty.xml + DifficultyAdapter。
 *
 * 15 档全列, 每档带"这档对手大概是什么水平"的段位标签和一句说明, 左侧色条
 * 按段位取色。之前小程序把这 15 格塞在首页, 首屏一半被难度网格占掉, 而 App
 * 的首页根本没有它 —— 它是对局中随时能点开的选择器。
 *
 * Lv.10 以上端侧 JS 引擎跑不动 (见 engine-profile.miniprogramMaxLevel),
 * 灰掉但**仍然列出来**: 让用户知道这些档位存在、在哪能用, 比直接藏掉诚实。
 */
const { difficulties, engineProfile } = require('../../generated/product-config');
const palette = require('../../core/palette');
const i18n = require('../../core/i18n');

Component({
  properties: {
    show: { type: Boolean, value: false },
    current: { type: Number, value: 4 },
    theme: { type: String, value: 'dark' },
  },

  data: {
    levels: [],
    maxLocalLevel: engineProfile.miniprogramMaxLevel,
    title: '',
    cancelText: '',
    localHint: '',
  },

  observers: {
    theme() {
      this.rebuild();
    },
  },

  lifetimes: {
    attached() {
      this.rebuild();
    },
  },

  methods: {
    rebuild() {
      const theme = this.data.theme;
      const max = engineProfile.miniprogramMaxLevel;
      this.setData({
        levels: difficulties.map((item) => ({
          ...item,
          color: palette.tone(theme, item.tone),
          appOnly: item.level > max,
        })),
        title: i18n.t('menu_difficulty'),
        cancelText: i18n.t('dialog_cancel'),
        localHint: i18n.t('mp_local_engine_range', max),
      });
    },

    onPick(event) {
      const level = Number(event.currentTarget.dataset.level);
      if (level > this.data.maxLocalLevel) {
        wx.showToast({ title: i18n.t('mp_level_app_only', level), icon: 'none' });
        return;
      }
      this.triggerEvent('pick', { level });
    },

    onClose() {
      this.triggerEvent('close');
    },

    /** 吃掉面板内的点击, 免得穿透到遮罩把面板关掉 */
    noop() {},
  },
});
