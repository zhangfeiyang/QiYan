/**
 * canvas 用的调色板。
 *
 * wxss 里的 CSS 变量 canvas 读不到, 所以棋盘和胜率条这两个画出来的控件需要
 * 一份 JS 版本。色值必须与 app.wxss / Android colors.xml 逐个对齐 —— 改了
 * 一边没改另一边, 棋盘和它周围的界面就会是两个色温。
 */

const DARK = {
  surfaceSunken: '#1D1B19',
  surfaceBase: '#262421',
  borderSubtle: '#3D3936',
  textPrimary: '#EFECE7',
  textSecondary: '#ADA79F',
  textTertiary: '#7A756E',
  textOnAction: '#16210B',
  action: '#81B64C',
  danger: '#E0574A',
  warning: '#E3A13C',

  boardGrad: ['#D9C39B', '#CBB489', '#BFA579', '#AD9163'],
  boardLine: '#4A3826',
  boardPalace: '#6B4A2A',
  boardBorder: '#1A1613',
  boardCoord: '#7A5D3A',
  boardGrain: 'rgba(160,130,109,0.50)',

  pieceRed: '#B4302A',
  pieceRedEdge: '#7C1A16',
  pieceBlack: '#22201E',
  pieceGrad: ['#F7EBD2', '#EADAB6', '#D6BE92'],
  pieceGrain: 'rgba(160,130,109,0.19)',
  pieceShadow: 'rgba(0,0,0,0.35)',

  hlSelect: 'rgba(242,201,76,0.60)',
  hlMove: 'rgba(95,168,62,0.60)',
  hlMoveHalo: 'rgba(95,168,62,0.30)',
  hlCapture: 'rgba(214,69,65,0.60)',
  hlLast: 'rgba(227,161,60,0.50)',
  hlHintSoft: 'rgba(91,157,217,0.40)',
  hlCheck: 'rgba(224,87,74,0.60)',
  hlArrow: '#3F9E45',
  selRing: '#FFC24D',
  fxSpark: '#FFD24A',

  winrateRedSoft: '#E8918A',
  winrateRedStrong: '#D6483F',
  winrateBlackSoft: '#B8B2AA',
  winrateBlackStrong: '#E8E4DE',
  winrateTextOnRed: '#FFFFFF',
  winrateTextOnBlack: '#262421',
};

const LIGHT = {
  ...DARK,
  surfaceSunken: '#E6E2DB',
  surfaceBase: '#F4F2EE',
  borderSubtle: '#DEDAD3',
  textPrimary: '#23211F',
  textSecondary: '#5E5952',
  textTertiary: '#8E8880',
  textOnAction: '#FFFFFF',
  action: '#55802D',
  danger: '#C4362B',
  warning: '#B77A16',

  boardGrad: ['#EBD9B4', '#DDC49A', '#D2B288', '#C0A272'],
  boardBorder: '#8A6E48',

  pieceGrad: ['#FFF8DC', '#F2E2BF', '#DEC69B'],
  pieceShadow: 'rgba(0,0,0,0.25)',

  hlHintSoft: 'rgba(91,157,217,0.40)',
  hlArrow: '#2E7D32',
  selRing: '#F2A03C',
  fxSpark: '#FFB020',

  winrateRedSoft: '#E08B84',
  winrateRedStrong: '#C0392B',
  winrateBlackSoft: '#6E6A66',
  winrateBlackStrong: '#2C2A28',
  winrateTextOnRed: '#FFFFFF',
  winrateTextOnBlack: '#FFFFFF',
};

/** 段位/难度色调, 与 colors.xml 的 rank_* 一致 */
const TONES = {
  dark: {
    bronze: '#C87F3F', silver: '#BFBFC4', gold: '#E8C04A', platinum: '#D8E0E4',
    diamond: '#7FD9EF', master: '#FF7043', grandmaster: '#B57BE0', king: '#FFD54F',
  },
  light: {
    bronze: '#B06D33', silver: '#8C8C94', gold: '#C79A1E', platinum: '#6E8A96',
    diamond: '#2E9BB8', master: '#E2551F', grandmaster: '#8E4EC6', king: '#C79A1E',
  },
};

const get = (theme) => (theme === 'light' ? LIGHT : DARK);
const tone = (theme, name) => (TONES[theme === 'light' ? 'light' : 'dark'][name] || TONES.dark.gold);

module.exports = { get, tone, DARK, LIGHT };
