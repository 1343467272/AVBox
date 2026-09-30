package com.github.tvbox.osc.ui.activity

/**
 * 详情页 → 播放层的单向下行指令(VM 归一 V2:`DetailViewModel` 曾直持 `PlayContainer` 引用,
 * 既违反「VM 零 View 引用」,又出现 VM 同步读 View 状态的写法)。
 *
 * 指令由 UI(组合层)收集后投影到容器,等价于旧实现里的同名直调。
 */
sealed interface PlaybackCommand {

    /** 同页换片:停掉当前内容(旧 `PlayContainer.stopForContentSwitch`) */
    data object StopForContentSwitch : PlaybackCommand

    /** 换源点击即停:停播并提示(tip 已在 VM 侧取好文案 —— 指令在 VM 创建,不能只带 resId) */
    data class StopForSourceSwitch(val tip: String) : PlaybackCommand

    /** 清除"正在切换片源"提示 */
    data object ClearSourceSwitchTip : PlaybackCommand

    /** 选集面板显隐(供播放底栏冻结自动收起) */
    data class SetEpisodeSheetOpen(val open: Boolean) : PlaybackCommand

    /** 切换清晰度(选中结果由容器经 `PlayContainer.OnQualitySelectedListener` 回写 VM) */
    data class SelectQuality(val position: Int) : PlaybackCommand
}

/**
 * 「进全屏/退全屏」决策所需的设备事实。
 *
 * 两项都不是 VM 能自己知道的东西,且**必须由 UI 在调用当帧同步传入**:
 * - `landscape`:方向从 Activity 的 resources 读,VM 无 Context 可读;
 * - `portraitVideo`:视频尺寸就绪信息在播放层(`MyVideoView.isPortraitVideo`),走状态通道会晚一帧 ——
 *   而 `rotating` 要当帧交给布局,晚一帧会先按错误的形态排一帧再纠正。
 *
 * 传事实(而不是传 View)既让 VM 保持零 View 引用,也保留旧实现"同一帧内决策"的语义。
 */
data class DetailPlaybackFacts(
    val landscape: Boolean,
    val portraitVideo: Boolean,
)

internal object DetailPlaybackCommands {

    /**
     * 全屏形态决策,返回 `(fullScreen, rotating)`。逐字保留旧 `DetailViewModel.setFullScreen` 的判据:
     *
     * ```
     * landNow    = 窗口方向 == LANDSCAPE
     * landTarget = requested && !portraitVideo
     * rotating   = landTarget != landNow
     * ```
     *
     * **退全屏不是短路分支**:`requested=false` 时 `landTarget` 恒 false,于是 `rotating = landNow` ——
     * 窗口还横着退全屏必须置位,`fullBox`(= `if (rotating) isLandscapeNow else full`)才会保持全屏形态
     * 直到系统旋转落地,这正是 2026-09-13 真机验证过的「横屏按返回:画面保持全屏样转回竖屏,
     * 不再缩小靠左上跳」(`skill/history/features.md` 的「全屏/退出全屏旋转过渡修复 A+B」,
     * 另见 features.md 记的「不要为它盲改判据」)。写成 `if (!requested) return false to false` 会退回那个已修的观感缺陷。
     *
     * `rotating` 的进全屏侧:竖屏窗口 + 横屏视频 ⇒ 置位;竖屏视频 ⇒ 目标形态非横屏 ⇒ 不置位
     * (竖屏→竖屏不触发 `onConfigurationChanged`,置位会永不复位 —— 见 features.md 的「rotating 语义」条,
     * 真正的锁竖屏在 `DetailActivity.applyFullscreen` 的 `SENSOR_PORTRAIT` 分支)。
     *
     * 真值表 6 格全部锁在 `DetailPlaybackCommandsTest`。
     */
    fun fullScreenState(
        requested: Boolean,
        facts: DetailPlaybackFacts,
    ): Pair<Boolean, Boolean> {
        val landscapeTarget = requested && !facts.portraitVideo
        return requested to (landscapeTarget != facts.landscape)
    }
}
