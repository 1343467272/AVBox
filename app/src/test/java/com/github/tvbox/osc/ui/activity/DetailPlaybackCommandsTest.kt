package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 语义单测:全屏形态决策原本写在 `DetailViewModel.setFullScreen`(且顺带读 View 状态),
 * 抽成纯函数后在此按旧表达式**逐格**锁行为 —— 避免"搬位置"时把语义搬丢。
 *
 * 旧表达式(`skill/history/features.md`「全屏/退出全屏旋转过渡修复 A+B」+「rotating 语义」两条):
 * ```
 * landNow    = 窗口方向 == LANDSCAPE
 * landTarget = requested && !竖屏视频
 * rotating   = landTarget != landNow
 * ```
 * 下面 8 个用例把 2×2×2 = 8 格真值表全部覆盖。两格**不是**恒 false(详见对应用例),
 * 写成 `if (!requested) rotating = false` 会回归 2026-09-13 真机验证过的"横屏按返回不缩小"那条。
 */
class DetailPlaybackCommandsTest {

    private fun facts(landscape: Boolean, portraitVideo: Boolean = false) =
        DetailPlaybackFacts(landscape = landscape, portraitVideo = portraitVideo)

    private fun decide(requested: Boolean, landscape: Boolean, portraitVideo: Boolean = false) =
        DetailPlaybackCommands.fullScreenState(requested, facts(landscape, portraitVideo))

    // ——— 进全屏(requested = true)4 格 ———

    @Test
    fun enterFromPortraitWithLandscapeVideoRotates() {
        // 竖屏窗口 + 横屏视频:目标横屏 ≠ 当前竖屏 ⇒ 置 rotating(先按目标形态铺版,系统旋转随后到位)
        val (full, rotating) = decide(requested = true, landscape = false)
        assertTrue(full)
        assertTrue(rotating)
    }

    @Test
    fun enterFromPortraitWithPortraitVideoDoesNotRotate() {
        // 竖屏窗口 + 竖屏视频:landTarget=false == landNow=false ⇒ 不置位。
        // 竖屏→竖屏不触发 onConfigurationChanged,置位会永不复位(features.md「rotating 语义」条)
        val (full, rotating) = decide(requested = true, landscape = false, portraitVideo = true)
        assertTrue(full)
        assertFalse(rotating)
    }

    @Test
    fun enterFromLandscapeWithLandscapeVideoDoesNotRotate() {
        // 横屏窗口 + 横屏视频:大屏点全屏时系统可能不旋转,目标与当前同为横屏 ⇒ 不置位
        val (full, rotating) = decide(requested = true, landscape = true)
        assertTrue(full)
        assertFalse(rotating)
    }

    @Test
    fun enterFromLandscapeWithPortraitVideoRotatesBack() {
        // 横屏窗口 + 竖屏视频:目标竖屏 ≠ 当前横屏 ⇒ 置位(转回竖屏)
        val (full, rotating) = decide(requested = true, landscape = true, portraitVideo = true)
        assertTrue(full)
        assertTrue(rotating)
    }

    // ——— 退全屏(requested = false)4 格 ———

    @Test
    fun exitFromLandscapeWithPortraitVideoKeepsFullBoxUntilRotationLands() {
        // ★ 易被误"简化"成恒 false 的一格:窗口还横着退全屏 ⇒ rotating=true,
        // fullBox = if (rotating) isLandscapeNow else full 保持全屏样,直到 onConfigurationChanged 清位。
        // 这正是 2026-09-13 修掉的"缩小靠左上跳",不要为"看起来更干净"改掉
        val (full, rotating) = decide(requested = false, landscape = true, portraitVideo = true)
        assertFalse(full)
        assertTrue(rotating)
    }

    @Test
    fun exitFromLandscapeWithLandscapeVideoAlsoKeepsFullBox() {
        // ★ 同一格的另一支:目标(退全屏=竖屏)与当前横屏不等 ⇒ 同样置位
        val (full, rotating) = decide(requested = false, landscape = true)
        assertFalse(full)
        assertTrue(rotating)
    }

    @Test
    fun exitFromPortraitWithLandscapeVideoDoesNotRotate() {
        // 竖屏窗口 + 横屏视频退全屏:requested=false 令 landTarget=false,与 landNow=false 相等 ⇒ 不置位
        val (full, rotating) = decide(requested = false, landscape = false)
        assertFalse(full)
        assertFalse(rotating)
    }

    @Test
    fun exitFromPortraitWithPortraitVideoDoesNotRotate() {
        // 竖屏窗口 + 竖屏视频退全屏:同上,两格皆 false
        val (full, rotating) = decide(requested = false, landscape = false, portraitVideo = true)
        assertFalse(full)
        assertFalse(rotating)
    }
}
