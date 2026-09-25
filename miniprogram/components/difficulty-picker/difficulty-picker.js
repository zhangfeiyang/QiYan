/** Five shared difficulty tiers; higher native-engine levels remain clearly marked. */
const { difficulties, engineProfile } = require('../../generated/product-config');
const palette = require('../../core/palette');
const i18n = require('../../core/i18n');

Component({
  properties: {
    show: { type: Boolean, value: false },
    current: { type: Number, value: engineProfile.defaultLevel },
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
          name: i18n.t(item.nameKey),
          segment: i18n.t(item.segmentKey),
          desc: i18n.t(item.descKey),
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
