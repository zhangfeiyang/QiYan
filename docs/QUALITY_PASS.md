# 质量打磨（2026-09-25）

在模拟器上以全新安装的用户身份走完每一屏（中/英文 × 浅/深色），先找问题再改。
下面每一条都在模拟器上复现过，修完又回到同一屏确认过。

## 功能缺陷

| 问题 | 现象 | 处理 |
|---|---|---|
| 结算后「悔棋继续」卡死 | 继续下完后认输没反应，将死不弹结算；引擎被要求在无子可走时出招 | 终局与积分结算拆开：结算只做一次，终局可以多次；续下的部分不计分，棋谱覆盖同一条记录 |
| 执黑时悔棋卡死 | 盘上只有引擎第一手时悔棋，会退掉引擎那手然后没人再走 | 没有玩家的棋可悔时不执行 |
| 对手头像是素色圆片 | GradientDrawable 在有滤色器、没有 solid 时整只圆被填满，同色的字看不见 | 刻环改用 setStroke；八档各配一种在日/夜象牙面上都 ≥3.4:1 的字色；棋子阶梯改为 兵马炮车将将帥帥 |
| 新对局只列到第六档 | RecyclerView 放在 ScrollView 里只摆出一屏，「棋圣」「巅峰」选不到 | 换成 NestedScrollView（对局内的难度弹窗一并改） |
| 金色主按钮下有一道横条 | 高光层下半是直角，胶囊弧角外露出矩形 | 高光层四角同为胶囊圆角 |
| 结算卡夜间隐身 | 卡片底色写死为浅米色，主结论和「继续对局」按钮的字在夜间翻成浅色 | 卡内字色改为不随主题翻转的深墨色；速拼结算卡同理，得分数字对比度 1.7:1 → 4.6:1 |
| 计分可刷 | 计分局中途换边等于接手引擎的局面；中途改高档位按高档结算 | 下过棋后换边会先确认，并转为练习；闯关执子固定。积分按本局出现过的最低档结算 |
| 速拼跳过双扣命 | 引擎应手途中跳过，迟到的应手会落到新题上或再扣一颗心 | 换题/答完即作废在途应手 |
| 每日一题打卡不提示 | 用杀法（如马后炮）解开每日一题时，只报杀法名，不说第几天、连续几天 | 杀法与打卡两行都显示 |

## 交互与视觉

- **提示给出着法**：顶栏胶囊在“AI 思考中”之后写出推荐着法（如「提示 · 马八进七」），读屏也能念。去掉只写“提示”两字的 Toast。
- **残局速拼开赛说明**：以前一进页面就开始倒计时。现在先看规则（红先、走错或跳过扣一颗心、越答越难）和个人最佳，按「开始速拼」才计时；跳过按钮写明「扣一颗心」。
- **首页对手行紧贴开局按钮**：它决定「开始」开的是哪一盘，以前被两张特色卡隔开。
- **设置页**：线程/哈希移到最后的「高级 · AI 引擎」，第一屏是声音、棋盘、记谱。
- **系统弹窗接入主题**：确认框从冷灰直角改为纸色/暖炭圆角，与页面同一套配色。
- **闯关地图**：远峰、中山底边渐隐，去掉两道横向硬边；终点「通关」字在夜间不再隐身。
- **杀法宝典**：emoji（双车错配的是一辆赛车）换成该杀法主子的棋子徽记；标题按题库写「11 式」，不再写死“十大”；研习状态改用胶囊。
- **残局研习卡片**：标题只留题眼（「闷宫」「重炮」），不再是被省略号截断的「Mate in 1 · Smother…」。
- **对局信息行**：英文开局名过长时省略，不再把段位与手数挤出屏幕。
- 英文文案：认输后的“Narrow Loss”改为“Defeat”，“Draw Agreed”改为“Draw”，连续天数不再出现“1 days”。

## 留存与成长

- **结算页「复盘本局」**：下完一盘最该做的是看哪一步走坏了，以前要退回首页、进复盘列表再找这一盘。
- **定级赛**：新档案 RD=350，第一盘计分局输给「进阶」就会从业余三段掉到业余初段。现在前 3 盘只定分不授印，首页和段位印鉴显示“定级 n/3”，第 3 盘结算揭晓首枚段位印。已有 3 盘以上计分局的老档案不受影响。
- **新手每日一题**：前 7 次只从一两步杀里出，避免第一天就轮到六步杀，连续打卡还没开始就断。

## 验证

- `:app:assembleDebug` 通过；`:app:testDebugUnitTest` 44 个测试类 244 项全部通过（新增 RankLadder 定级、每日一题入门题池、题眼标题的单元测试）。
- `:app:lintDebug` 0 Error（原有一处 MissingSuperCall：速拼页覆写已废弃的 onBackPressed，改用 OnBackPressedDispatcher）。
- `node tools/sync-shared-config.mjs` 重新生成小程序文案表，`node tools/check-platform-sync.mjs` 通过；小程序与工具的 5 组 Node 测试通过。
- 模拟器 `emulator-5554`（调试包 `com.zhongguoxiangqi.debug`）逐屏复核：结算→继续→再次认输弹出结算；「复盘本局」直达该局；定级赛 1/3→2/3→3/3 的行文；换边确认；速拼开赛前计时停在 03:00；每日一题解开后首页记为第 1 天。

## 没有改的

- 计分局里的悔棋和提示仍然不影响积分，这是既有设计（“悔棋不扣分”写在界面上）。但提示用的是专业引擎，每步都按提示可以赢下大师档。建议之后决定是否让“用过提示的计分局”按练习处理。
- 复盘列表里历史对局旁显示的段位，仍按当时的分数换算，定级期间的对局也会显示段位名。

## 改动文件

工作区在这一轮之前已有大量未提交改动，下面列出本轮动过的文件，便于分开审阅/提交。
小程序 `miniprogram/generated/` 下三个文件由 `node tools/sync-shared-config.mjs` 重新生成。

新增：
- `app/src/main/java/com/zhongguoxiangqi/ui/puzzle/PuzzleTitles.kt`
- `app/src/main/res/drawable/bg_dialog_window.xml`
- `app/src/main/res/drawable/bg_piece_medallion.xml`
- `app/src/main/res/values-night/quality_theme_colors.xml`
- `app/src/main/res/values-zh/quality_refresh.xml`
- `app/src/main/res/values/quality_colors.xml`
- `app/src/main/res/values/quality_refresh.xml`
- `app/src/main/res/values/quality_theme_colors.xml`
- `app/src/test/java/com/zhongguoxiangqi/ui/puzzle/PuzzleTitlesTest.kt`
- `docs/QUALITY_PASS.md`

修改：
- `README.md`
- `app/src/main/java/com/zhongguoxiangqi/data/DailyPractice.kt`
- `app/src/main/java/com/zhongguoxiangqi/data/ProgressStore.kt`
- `app/src/main/java/com/zhongguoxiangqi/game/TacticsAcademy.kt`
- `app/src/main/java/com/zhongguoxiangqi/rating/RankLadder.kt`
- `app/src/main/java/com/zhongguoxiangqi/rating/UserProfile.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/campaign/CampaignPathView.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/game/GameActivity.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/game/GameViewModel.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/menu/MainMenuActivity.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/menu/OpponentAvatar.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/menu/RankHallActivity.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/puzzle/PuzzleActivity.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/puzzle/PuzzleListActivity.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/puzzle/PuzzleRushActivity.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/puzzle/PuzzleRushViewModel.kt`
- `app/src/main/java/com/zhongguoxiangqi/ui/puzzle/TacticsAcademyActivity.kt`
- `app/src/main/res/drawable/bg_btn_gold.xml`
- `app/src/main/res/drawable/bg_opponent_avatar.xml`
- `app/src/main/res/drawable/ink_peaks_far.xml`
- `app/src/main/res/drawable/ink_peaks_mid.xml`
- `app/src/main/res/layout/activity_game.xml`
- `app/src/main/res/layout/activity_main_menu.xml`
- `app/src/main/res/layout/activity_new_game.xml`
- `app/src/main/res/layout/activity_puzzle_list.xml`
- `app/src/main/res/layout/activity_puzzle_rush.xml`
- `app/src/main/res/layout/activity_settings.xml`
- `app/src/main/res/layout/activity_tactics_academy.xml`
- `app/src/main/res/layout/dialog_difficulty.xml`
- `app/src/main/res/layout/dialog_game_result.xml`
- `app/src/main/res/layout/dialog_puzzle_rush_result.xml`
- `app/src/main/res/layout/item_puzzle_card.xml`
- `app/src/main/res/layout/item_tactic_academy.xml`
- `app/src/main/res/values-night/themes.xml`
- `app/src/main/res/values-zh/home_refresh.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/main/res/values/home_refresh.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/test/java/com/zhongguoxiangqi/data/DailyPracticeTest.kt`
- `app/src/test/java/com/zhongguoxiangqi/rating/RankLadderTest.kt`
