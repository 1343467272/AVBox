---
name: AVBox 渐进式重构 Spec（VM 归一与上帝类收尾专项）
status: 待执行（2026-10-01 立项，同日 D1/D2/V6 拍板：全部按建议；本文接手 refactor-plan-20260928.md 的阶段 7 遗留与其未做项中与本专项重叠的部分）
source: 2026-10-01 用户提名的四问题审查：① 双范式并存（LiveData/StateFlow 各半、viewmodel/ 名不副实）② 上帝类残留（PlaybackController / ComposeVideoController / PlayContainer / ApiConfig）③ VM 持 View 与 static 可变缓存 ④ 业务逻辑写进 Composable。审查结论：四问题全部属实（数字 ±5% 出入见 §2）
---

# 结论摘要

- 四问题按根因归并为三条线：**问题 ③+① 后半同根**（页面级 VM 与站点取数门面的耦合方式——static 缓存 + 换实例 + observeForever 手动配对）；**问题 ④ 独立**（新页面没按既有 VM 规范写）；**问题 ② 是两件事**（播放三件套 = 播放服务化 P0–P5 完成后的结构遗留；ApiConfig = 旧 spec 已登记未做的直播解析链）。
- 动手顺序（风险从低到高）：**V1** ConfigManageViewModel 抽取 → **V2** `playContainerRef` 去引用 → **V3** static 状态外迁 + `viewmodel/` 包改名 → **V4** LiveData 观察侧收口 → **V5** 播放三件套结构拆分 → **V6**（可选）ApiConfig 直播解析链。
- 终态判据：UI 层 Composable 零业务编排；ViewModel 零 View 引用、零 static 可变字段；`observeForever` 全库 ≤1 文件（桥接器内部）；`viewmodel/` 包名实至名归；播放三件套各主文件按簇可单测（不设行数硬指标）。
- 继承旧 spec 三不变：不引入 DI、不重写 Java→Kotlin、不为行数达标硬拆（D2 拍板后 LiveData 分域方案不再涉及任何 Java→Kotlin 重写）。
- 每步独立 commit（全英文小写 + scope）、可独立回滚；每步落地跑 `:app:assembleDebug` + `:app:testDebugUnitTest`。

# 1. 与既有 spec 的关系（先读，避免重复立项）

| 既有文档 | 关系 |
| --- | --- |
| `refactor-plan-20260928.md` | 阶段 1–6 已落地。**阶段 7（PlaybackController）原"不独立立项、并入播放服务化"，现播放服务化 P0–P5 已全部完成而拆分未发生——本 spec 的 V5 正式接手**，拆解依据沿用其登记的 4 状态簇。阶段 5 的 viewmodel/ 11 文件结构（门面 168 行 + 5 Loader + 3 支撑类 + 2 VM）是刻意产物，本 spec 处理的是它的"包名与职责错位"，不是否定拆分本身。 |
| `avbox-playback-service-spec.md` | P0–P5 已落地（2026-09-14 真机回归通过）。`PlaybackController` 2203→2016 行是 P1"整体搬进"的结果（当时未拆）；`PlayContainer` 3074→1551→1407 行只剩"视图 + 控制器 + 挂摘 + 生命周期"。V5 在其**终态结构之上**做簇拆分，不回退任何服务化语义。 |
| `avbox-code-review-spec.md` | 严重度锚点与注释红线（⑪）继续引用；本 spec 不处理注释存量。 |

# 2. 现状基线（2026-10-01 实测）

| 度量 | 值 | 说明 |
| --- | --- | --- |
| `PlaybackController.java` | 2016 行 / ~129 方法 | 含自持 `SourceViewModel` + `playResultObserver`（1039–1040 行）与 1075 行 107 行匿名 `Observer`（旧 spec 阶段 2 遗留，归 V5） |
| `ComposeVideoController.kt` | 1423 行 / 137 fun | `BaseVideoController` 基类 + 5 接口（`PlayerControlApi`/`PlayerActions`/`OnGestureListener`/`OnDoubleTapListener`/`OnTouchListener`） |
| `PlayContainer.java` | 1407 行 | 服务化后剩视图/控制器装配/挂摘；已两轮拆分（1839→1424→1407） |
| `ApiConfig.java` | 1054 行 / 70 方法 | 直播解析链（`parseLive*`/`loadLives`/`loadLiveApi`/`initLiveSettings`）≈420 行未拆 |
| `DetailViewModel.kt` | 959 行 / 62+ 方法 | 14 个 `MutableStateFlow`；`playContainerRef` 7 处使用 |
| `ConfigManagePage.kt` | 953 行 | `ConfigManageScreen` 170→703 行单函数；17 个 `remember`、14 个嵌套业务 fun；全文件零 ViewModel |
| `SourceViewModel.java` | 168 行门面 | 7 个 `MutableLiveData` 通道 + `static sortCache`/`extendCache` + `spThreadPool` 别名 |
| `observeForever` 手动配对 | **9 处 / 5 文件** | `DetailViewModel`(131/217)、`HomeViewModel`(110–112/393，持 3+1 实例)、`PartitionListViewModel`(51/66 两实例)、`SearchViewModel`(302)、`PlaybackController`(1039) |
| `viewmodel/` 包 | 11 文件 | 真正的 ViewModel 仅 2 个（`SourceViewModel`/`SubtitleViewModel`），其余 9 个为 Loader/Resolver/Helper/Parser |

# 3. 问题清单（映射用户提法 → 根因）

| # | 用户提法 | 审查确认 | 根因归属 | 处理阶段 |
| --- | --- | --- | --- | --- |
| ①a | Kotlin VM 用 StateFlow、Java VM 用 LiveData，范式割裂 | 属实（14 vs 7） | 通道与观察桥接无统一边界 | V4 |
| ①b | `observeForever` 那个 Java VM | 属实且面更大：5 文件 9 处手动配对，配对缺失即泄漏 | "换实例防迟到回包"设计（`rebindDetailSource`）倒逼出的手法 | V3/V4 |
| ①c | viewmodel/ 11 文件 9 个名不副实 | 属实；系旧 spec 阶段 5 刻意同包（包级可见性 40 个成员复用） | 包名与职责错位，非结构问题 | V3 |
| ②a | 播放三件套上帝类 | 属实 | 播放服务化 P1"整体搬进"未拆 + Compose 控制器多角色 | V5 |
| ②b | ApiConfig 1054 行 | 属实 | 旧 spec 阶段 4 明示未做（直播解析链） | V6（可选） |
| ③a | `DetailViewModel.kt:79` 持 `PlayContainer` | 属实，7 处使用（含 232–233 行同步读 View 状态） | VM→View 无指令通道，只能直拿引用 | V2 |
| ③b | 页面级 VM 带 `static sortCache` | 属实（+ `static extendCache`、`spThreadPool` 别名） | 换实例设计要求状态逃生 VM 生命周期 → 只能 static | V3（与 ①b 同因果链） |
| ④ | 订阅增删改/切源/黑名单在 UI 作用域 | 属实；加重项：`deleteSelected` 内一次性 executor + `AppBootstrap.retry()` 全局副作用 | 页面未按既有 VM 规范写 | V1 |

# 4. 目标终态（验收清单）

1. `ConfigManagePage.kt` 的 Composable 只含 UI 组合与事件转发；订阅/切源/黑名单/副本清理全在 `ConfigManageViewModel`。
2. 全库 ViewModel（含页面级）**零 View 类型引用**、**零 static 可变字段**（`spThreadPool` 类常量除外，见 D2 说明）。
3. `observeForever` 全库 ≤1 文件（观察桥接器内部）；Kotlin 侧页面 VM 只见 Flow。
4. `com.github.tvbox.osc.viewmodel` 包不复存在——整包改名 `com.github.tvbox.osc.sourcedata`（D1 已拍板）。
5. `PlaybackController` 按簇拆出 ≥3 个可单测协作者，主类 ≤600 行量级（软目标）；107 行匿名 `Observer` 具名化。
6. `PlayContainer` / `ComposeVideoController` 按簇评估，拆或不拆给逐簇结论（不为行数硬拆，V5 出口条件是"每个簇有归属与单测/豁免理由"）。
7. `ApiConfig` 直播链落地或明示豁免（V6，用户拍板）。

# 5. 渐进式计划

## V1｜ConfigManageViewModel 抽取（风险最低，消问题 ④）

- **修改**：新增 `ui/page/ConfigManageViewModel.kt`（Kotlin + `MutableStateFlow`，与 `DetailViewModel` 同范式）。
  - 状态入 VM：`vodItems`/`liveItems`/`activeUrl`/`liveActiveUrl`/`liveFollow`/`disabledUrls`/`selected`/`manageMode`/`pendingSwitch`/`editTarget`（`addDialogOpen`/`repoSheetOpen`/`mode` 等纯 UI 开关可留 Composable，逐个判断后在本节登记归属）。
  - 逻辑入 VM：14 个嵌套 fun（`switchToVod`/`switchToLive`/`requestSwitch`/`enableAndSwitch`/`followLiveNow`/`deleteSelected`/`commitAdd`/`commitEdit`/`refreshActiveSnapshot`/`exitManageMode`/`isInUse`/`activeInEitherMode`/`referencedBySubscribes`/`referencedByRepo`）+ 顶层 `loadSubscribes`/`saveSubscribe`/`updateSubscribe`/`applyVodSource`/`applyLiveSource`/`applyLiveFollowVod`。`badgeText` 是纯展示计算，留 UI 侧。
  - `ApiLineSignal` 收集（多仓改写刷新）入 VM。
  - `deleteSelected` 的一次性 `Executors.newSingleThreadExecutor()` → VM 内单一共享 executor 或复用 `SourceHelper` 池语义，杜绝"每次删除 new + shutdown"。
- **风险**：低-中。语义陷阱两处必须保留：`PendingSwitch` 带 `vod` 防 AnimatedContent 过渡期误切（注释已写明）；`disabledUrls`"只在首次组合读一次"的独立 Activity 前提——VM 化后天然成立（VM 随 Activity 重建），但删除/二次确认启用后的本地状态更新逻辑要与 BootGuard 落盘保持一致。
- **验证**：构建 + 单测；真机走查 = 订阅增/删（含"使用中"拦截）/改、切源成功与失败回滚、黑名单二次确认放行、删除后本地副本清理、多仓改写后"使用中"标记刷新。
- **Commit**：状态搬迁一笔、逻辑搬迁一笔（或合并一笔，登记后执行时定）。

## V2｜`playContainerRef` 去引用（消问题 ③a）

- **修改**：`DetailViewModel` 引入播放指令流 `playbackCommands: SharedFlow<DetailPlaybackCommand>`（sealed：`StopForContentSwitch` / `StopForSourceSwitch(tipRes)` / `ClearSourceSwitchTip` / `SetEpisodeSheetOpen(Boolean)` / `SelectQuality(Int)`），`DetailScreen`/`DetailEpisodes` 收集后调 `PlayContainer`；删除 79 行字段与 7 处使用，`DetailViewModel` 不再 import `PlayContainer`。
- **逐点注意**：
  - 775 行 `selectQuality(position)` 返回 `Boolean`（指令 + 确认）：改流式会丢返回值——把"选中结果"改为容器侧回写 VM 状态（`qualitySelected` 已存在，补一条确认通道），或该点暂留方法参数直传（在 VM 暴露 `fun requestQuality(position)`，内部仍发命令、由容器侧写结果）。**不允许**为图省事继续持 View。
  - 232–233 行 `playContainerRef?.resources?.configuration?.orientation` 与 `isPortraitVideo()` 是 VM 同步读 View 状态（反模式本体）：orientation 改从 `AppContextHolder`/`DetailActivity` 传入；`isPortraitVideo` 由容器在视频尺寸就绪时回写 VM 只读状态（新 `StateFlow<Boolean>`），`setFullScreen` 消费它。
- **风险**：中。播放指令从同步调用变异步分发，注意 `applyTarget`(170 行) 的 `stopForContentSwitch` 时序——换片时旧内容必须先停，命令发出到消费在下一帧，验证切集/换片瞬间的画面与声音串扰。
- **验证**：构建 + 单测（指令流补一条 vm 逻辑单测）；真机走查 = 切集、换源（含失败回滚 tip 的出现与消除）、全屏/旋转判定、选集面板打开、清晰度选择。
- **Commit**：一笔。

## V3｜static 状态外迁 + `viewmodel/` 包改名（消问题 ①c + ③b）

- **修改（两步，各自独立 commit）**：
  1. **状态外迁**：新增 `viewmodel/SourceRuntimeState.java`（终名随 D1），收编 `sortCache`/`extendCache`/`clearRuntimeCache()` 与 `spThreadPool` 别名的真实持有者角色（`SPIDER_POOL` 已在 `SourceHelper`，只需改调用点直引后删门面别名）。`SortLoader`/`SourceViewModel` 改为通过它存取；`HomeViewModel:150` 的 `SourceViewModel.clearRuntimeCache()` 调用点改指新家。**保留 `aa5b132` 的 access-order 加锁语义**（`synchronized (sortCache)` + 持锁不做 IO）。
  2. **包改名**（D1 已拍板：`sourcedata`）：`com.github.tvbox.osc.viewmodel` → `com.github.tvbox.osc.sourcedata`，**整包原样改名**，包内 40 个包级成员的可见性语义不变（这是改名而非重拆的唯一理由）。
- **前置检查（硬约束级）**：全库字符串检索 `com.github.tvbox.osc.viewmodel` 与各类名（含 `AndroidManifest`、`proguard-rules`、KV 字符串值、jar 契约面）——spider jar 理论上不依赖该包（其契约在 `catvod.crawler`/`catvod.bean`），但必须检索后才能动手。
- **风险**：低（纯机械替换 + 编译验证）。（备选"包内重拆"违反包级可见性前提，已随 D1 拍板驳回。）
- **验证**：构建 + 单测（`SortLoader`/`SourceRuntimeState` 的缓存行为已有用例则迁移，无则补 LRU 上限 5 条 + 清空两条）；真机走查 = 换源后 `sortCache` 清理仍生效（旧 spec 阶段 5 的判据沿用）。
- **Commit**：外迁一笔、改名一笔。

## V4｜LiveData 观察侧收口（消问题 ①a + ①b）

- **终态（D2 已拍板：分域 + 单桥接器）**：**按语言分域**——`sourcedata` 包内 Java 侧继续 LiveData（Java 写 `StateFlow` 无语言便利，重写 9 文件违反"不重写 Java→Kotlin"且收益/风险比不成立）；Kotlin 侧页面 VM（`DetailViewModel`/`HomeViewModel`/`SearchViewModel`/`PartitionListViewModel`）只见 Flow。
- **修改**：
  1. 新增 `viewmodel→sourcedata`（或 `ui/common`）下 Kotlin 桥接扩展 `LiveData<T>.observeAsFlow()``（lifecycle-livedata-ktx 的 `asFlow()` 若依赖可用则直接用，否则手写 callbackFlow 桥）——**全库唯一允许 `observeForever` 的位置**。
  2. 4 个页面 VM 的 9 处 `observeForever`/`removeObserver` 全部换 `flow` 收集（协程作用域随 VM）。`HomeViewModel` 内部 helper 类（373 行 `svm`）与 `PartitionListViewModel` 的 `actionViewModel` 同步处理。
  3. **`rebindDetailSource` 语义转换（本阶段核心）**：换 `SourceViewModel` 实例改为"单实例 + 代次 token"（`detailBuildToken` 已存在，扩展为所有 detail 回包携带 token、VM 侧失配即丢弃）。换实例需求消失后，`static sortCache` 的"实例逃生"动机同步消失（V3 已外迁，两步互为因果闭环）。`searchCaller` 的隔离语义（`searchToken`）核对后同样收敛。
  4. `PlaybackController`（Java，1039 行自持实例）**不改桥接**：它不是页面 VM， LiveData observe 是其自然写法；仅登记其在 V5 拆分时一并具名化 + 补配对清理检查。
- **风险**：中。token 失配丢弃逻辑必须覆盖：换片、fallback 换站、重试三条路径的迟到回包。**这是本 spec 最高语义风险点**（原"换实例"设计就是为隔离迟到回包，等价性判据 = 迟到回包绝不串进新内容）。
- **验证**：构建 + 单测（重点补：token 失配丢弃、token 匹配透传、fallback 链上 token 继承）；真机走查 = 详情快进快出连点（迟到回包不串片）、fallback 自动换站、搜索聚合并发、`HomeViewModel` sort/rec/action 三通道互不串扰。
- **Commit**：桥接器一笔、逐 VM 各一笔。

## V5｜播放三件套结构拆分（消问题 ②a；风险最高，最后做）

- **`PlaybackController` 2016 → 主类 ≤600 量级（软目标）**，按旧 spec 阶段 7 登记的簇 + 实测字段分布：
  | 簇 | 证据（行号） | 去向 |
  | --- | --- | --- |
  | 超时 | `MSG_RESOLVE_PLAY_URL_TIMEOUT` 等 3 MSG + 2 常量（437–444） | `PlaybackTimeouts` |
  | 嗅探 | `webPlayUrl`/`webHeaderMap`/`webUserAgent` + WebView 逻辑（1025–1027） | `SniffDelegate` |
  | 预载 | `preloadCoordinator`/`preloadReadyListener`（1614–1615） | 已半独立，正式收边 |
  | 取流观察 | `sourceViewModel`/`playResultObserver` + **1075 行 107 行匿名 Observer**（旧 spec 阶段 2 遗留） | 具名类 + 收口进主类或 `SniffDelegate` |
  | 进度继承 | `progressKey`/`progressOwner`/`inheritProgress*`（81–99） | 评估：若与 D6 同片接管耦合深则留主类并豁免 |
- **`PlayContainer` 1407**：逐簇评估（挂摘/生命周期段、全屏旋转段、弹幕字幕装配段、控制器装配段），**每簇给出"拆出 / 留下并写豁免理由"二选一结论**；已两轮拆分，警惕"为行数硬拆"（服务化后其职责本就是装配面）。
- **`ComposeVideoController` 1423**：先拆手势簇（`OnGestureListener`/`OnDoubleTapListener`/`OnTouchListener` 三个接口实现 → `GestureController` 委托，信号独立性最强）；`PlayerControlApi`/`PlayerActions` 的 UI 构建与回调转发簇拆分与否，在手势簇落地后按耦合实测再定。
- **风险**：高。全程"只搬位置不改逻辑"（播放服务化 R8 同款纪律）；每簇独立 commit，任何一簇回归即独立回滚。
- **验证**：构建 + 单测；真机走查按 `avbox-playback-service-spec.md` §4 的 1–14 全清单（该清单是本阶段回归基线，含 2026-09-21 待测的 11–14 项）。
- **Commit**：每簇一笔，预计 5–8 笔。

## V6｜（已拍板：不做）ApiConfig 直播解析链外迁

- 旧 spec 阶段 4 明示未做（≈420 行：`parseLive*`/`loadLives`/`loadLiveApi`/`initLiveSettings`），理由 = 直播可用性关键路径 + 当时阶段 3 走查未完成。现走查已过，风险敞口收窄，但**直播链路真机回归成本仍在**（清配置重载/多仓/线路切换/hosts 失效）。
- 不做的代价：ApiConfig 停在 ~1054 行，直播链与站源门面继续同文件。
- **已拍板不做（2026-10-01，按建议）**。若直播链后续出现回归需改动该区域，凭"单独排一次直播专项真机回归（判据：切直播源、线路历史、hosts 失效、跟随点播四路径）"的前提可重开。

# 6. 设计决策（D 系；2026-10-01 用户拍板"全部按建议"，⏳ 已清零）

| # | 问题 | 建议 | 状态 |
| --- | --- | --- | --- |
| D1 | `viewmodel/` 改名去向 | **整包改名 `com.github.tvbox.osc.sourcedata`**（站点取数与解析域；不并入 `data`，Room 域不混淆）。备选：改 `repository`（违反旧 spec"不引入 Repository 层"的表述直觉，弃）；不改名只加 package-info（未"彻底处理"，弃）。**前提 = 整包原样改名，不做包内重拆**（包级可见性 40 成员是同包复用的根基，重拆 = 把包级成员变 public，阶段 5 已验证该代价模式不可取）。 | ✅ 已拍板（2026-10-01）：按建议 |
| D2 | LiveData 是否全转 StateFlow | **按语言分域 + 单桥接器**（V4）。全库单范式的代价：`sourcedata` 9 个 Java 文件重写（违反旧 spec"不重写 Java→Kotlin"决策）或 Java 写 `StateFlow`（`setValue()` 可用但无语言便利，且 `postValue` 异步主线程语义要手工复刻）。翻案判据：`sourcedata` 包未来整体迁 Kotlin 时一并转。 | ✅ 已拍板（2026-10-01）：按建议 |
| D3 | `spThreadPool` 公开别名的去留 | `SPIDER_POOL` 真身在 `SourceHelper`；别名服务的是外部调用点（V3 时全库检索）。若外部调用点 ≤3 处改直引后删别名；若多则保留别名并登记为"稳定 API"。 | 执行时定 |
| D4 | `DetailViewModel` 自身 959 行是否拆 | V2/V3 改完后重估（fallback 换源簇 ~200 行、搜索簇 ~100 行是候选）。**不预设拆分**——它 62 个方法中一半是 VM 本职的事件转发。 | 执行时定 |
| D5 | V1 的 `ConfigManageViewModel` 作用域 | 独立 Activity 专属（`ConfigManageActivity` 壳 3KB），不共享、不挂载 Application 作用域。 | 定案 |

# 7. 明确不做

- 不引入 DI 容器（旧 spec §6 决策继承，翻案判据同彼）。
- 不拆 Gradle module；不动 `player` 模块上游内核（`tv.danmaku.ijk`、`xyz.doikki`）。
- 不为行数达标硬拆播放器状态机与 `PlayContainer` 装配面（V5 的出口条件是"簇有归属与理由"，不是数字）。
- 不把 `playContainerRef` 改 `WeakReference` 糊弄（治标：泄漏概率降但指令通道缺失与同步读 View 状态的原病都在）。
- 注释红线存量（⑪）、包级环剩余 17 组（旧 spec 阶段 6 收尾）、阶段 1b（Live 两簇）不在本 spec 范围。

# 8. 硬约束（违反即驳回）

- **迟到回包隔离语义不可弱化**（V4 第一验收）：换片/fallback/重试三路径的旧回包必须丢弃——"换实例"换"token"后此为全 spec 最高风险点。
- `sortCache` 的 access-order 加锁语义保留（`aa5b132`）：所有读写走 `synchronized (sortCache)`，持锁不做 IO。
- 任何类/包改名前：全库检索类名字符串（含 manifest/proguard/KV 值/jar 契约），不能只改 import（旧 spec §7）。
- KV 复杂键必须在 `KVKeySpec` 登记；`KV.contains` 才是存在性判断。
- BootGuard 白名单是唯一旋钮；黑名单状态的读改走 `BootGuard` 门面，V1 迁移时不得绕过。
- `ConfigParser` 字符集过滤、`VideoParseRuler.clearRule()` 的入口位置（V6 若做）不可动。
- 爬虫/网络阻塞调用在 IO 线程；`spThreadPool` 语义（共享池 + spider 阻塞）不得在迁移中变成每请求新建。
- Compose 状态提升位置正确性：`PendingSwitch` 带 `vod` 的 AnimatedContent 过渡陷阱在 V1 中必须保留。

# 9. 验证与回滚

- 每步落地：`.\gradlew.bat :app:assembleDebug` + `:app:testDebugUnitTest`；以 `BUILD SUCCESSFUL` 与 `test-results` 用例计数为准（**基线以 V1 执行日实跑为准**；09-28 参考值 45 类 376 例，其后 09-29/30 有内核移除与调色等增量提交）。
- 纯搬迁步（V3 改名、V5 各簇）：`git show HEAD:旧文件` 逐行去空白后多重集比对 + 方法级存在性检查（旧 spec 阶段 4/5 验证过的卡口，含 marker 唯一性教训）。
- 语义转换步（V2 指令流、V4 token）：必须先补单测锁行为再改实现（`token 失配丢弃`/`指令有序消费`）。
- 真机走查判据按阶段分列（见各节）；V5 绑定播放服务化 §4 全清单。
- 每步一个 commit，可独立回滚；不推远程除非明确许可。

# 10. 优先级与排期

| 阶段 | 消掉 | 估时 | 依赖 |
| --- | --- | --- | --- |
| V1 | ④ | 1 人日 | 无 |
| V2 | ③a | 0.5–1 人日 | 无 |
| V3 | ①c + ③b | 0.5–1 人日 | 无（D1 已拍板） |
| V4 | ①a + ①b | 2 人日 | V3（状态先归位，桥接才干净） |
| V5 | ②a | 3–4 人日 + 真机回归 1.5–2 人日 | 建议在 V1–V4 稳定一周后 |
| V6 | ②b | — | 已拍板不做（重开条件见 V6 节） |

总计核心路径（V1–V5）约 8–10 人日 + 两轮真机回归。建议节奏：V1+V2 一轮 → V3+V4 一轮 → 观察一周 → V5。

# 附录 A. 度量口径（可复现）

- 行数：`Get-ChildItem -Recurse -Include *.java,*.kt -File app\src\main | ForEach-Object { [pscustomobject]@{ Lines=[IO.File]::ReadAllLines($_.FullName).Count; Rel=$_.FullName } } | Sort-Object Lines -Descending`
- 方法数（Java，宽口径含匿名类覆盖）：`Select-String '^\s*(public|private|protected)\s+[\w<>\[\], .]+\s+\w+\('`；Kotlin fun：`Select-String '\bfun\s+\w+'`。**口径提示**：与用户 09-28 提法（129/133/76）存在 ±10% 差异，因宽窄口径不同——引用本 spec 数字时注明口径。
- `observeForever` 面：`Select-String '\.observeForever\('` 全库。
- VM 持 View 引用面：`Select-String 'PlayContainer\?|View\b'` 限 `**/viewmodel/**` 与页面 VM。

# 附录 B. 关键代码坐标（执行时直接定位）

- `DetailViewModel.playContainerRef`：声明 79 / 使用 170、232–233、525、552、775；赋值 `DetailScreen.kt:71`；`setEpisodeSheetOpen` `DetailEpisodes.kt:167`。
- `rebindDetailSource`：`DetailViewModel.kt:214–218`；`detailBuildToken`:98；`searchToken`:126。
- `observeForever` 九处：`DetailViewModel.kt:131/217`、`HomeViewModel.kt:110–112/393`、`PartitionListViewModel.kt:51/66`（实例创建）、`SearchViewModel.kt:302`（实例创建）、`PlaybackController.java:1039`。
- static 三件：`SourceViewModel.java:43`（`spThreadPool` 别名）/ 48（`sortCache`）/ 56（`extendCache`）/ 86（`clearRuntimeCache`）。
- `ConfigManagePage`：`ConfigManageScreen` 170–703；嵌套 fun 201–404；`deleteSelected` 的一次性 executor 358–360；`applyVodSource`/`applyLiveSource`/`applyLiveFollowVod` 148–166。
- `PlaybackController` 簇坐标：超时 437–444 / 嗅探 1025–1027 / 取流观察 1039–1040 + 匿名 Observer 1075 / 预载 1614–1615 / 进度继承 81–99。
- `HomeViewModel` 三实例：79–81；`clearRuntimeCache` 调用点 150。
