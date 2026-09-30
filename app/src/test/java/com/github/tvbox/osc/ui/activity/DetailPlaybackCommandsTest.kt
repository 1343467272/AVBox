package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 语义单测:全屏形态决策原本写在 `DetailViewModel.setFullScreen`(且顺带读 View 状态),
 * 抽成纯函数后在此按旧表达式逐条锁行为 —— 避免"搬位置"时把语义搬丢。
 *
 * 旧表达式:`landNow = 容器 resources 方向 == LANDSCAPE` / `landTarget = full && !竖屏视频` /
 * `rotating = landTarget != landNow`。下面覆盖它的全部真值组合。
 */
class DetailPlaybackCommandsTest {

    private fun facts(landscape: Boolean, portraitVideo: Boolean = false) =
        DetailPlaybackFacts(landscape = landscape, portraitVideo = portraitVideo)

    @Test
    fun enteringFromPortraitRequestsRotation() {
        // 竖屏窗口 + 横屏视频:目标横屏 ≠ 当前竖屏 ⇒ 置 rotating(先按目标形态铺版,系统旋转随后到位)
        val (full, rotating) = DetailPlaybackCommands.fullScreenState(true, facts(landscape = false))
        assertTrue(full)
        assertTrue(rotating)
    }

    @Test
    fun enteringWhenAlreadyLandscapeDoesNotRotate() {
        // 大屏设备点全屏时系统可能不旋转:窗口已是横屏、目标也是横屏 ⇒ 不置 rotating
        val (full, rotating) = DetailPlaybackCommands.fullScreenState(true, facts(landscape = true))
        assertTrue(full)
        assertFalse(rotating)
    }

    @Test
    fun portraitVideoFromPortraitWindowDoesNotRotate() {
        // 竖屏视频:landTarget 为 false,与 landNow=false 相等 ⇒ 不置 rotating。
        // 这才是 isPortraitVideo 在旧表达式里的真实作用(竖屏视频留在竖屏窗口,只换版式不转屏);
        // 面板"锁竖屏"(SENSOR_PORTRAIT 分支)另在 DetailActivity.applyFullscreen,不在本判据内。
        val (full, rotating) = DetailPlaybackCommands.fullScreenState(
            true,
            facts(landscape = false, portraitVideo = true),
        )
        assertTrue(full)
        assertFalse(rotating)
    }

    @Test
    fun portraitVideoFromLandscapeWindowRotatesBackToPortrait() {
        // 同一条判据的镜像:窗口已是横屏而内容是竖屏视频 ⇒ 目标竖屏 ≠ 当前横屏 ⇒ 置 rotating(转回竖屏)
        val (full, rotating) = DetailPlaybackCommands.fullScreenState(
            true,
            facts(landscape = true, portraitVideo = true),
        )
        assertTrue(full)
        assertTrue(rotating)
    }

    @Test
    fun exitingNeverRotates() {
        // 退全屏:目标形态恒为竖屏。窗口还在横屏(旋转尚未回位)时也必须清掉 rotating,
        // 否则 fullBox = if (rotating) isLandscapeNow else full 会悬挂在 rotating 上
        val portraitExit = DetailPlaybackCommands.fullScreenState(false, facts(landscape = false))
        assertFalse(portraitExit.first)
        assertFalse(portraitExit.second)
        val landscapeExit = DetailPlaybackCommands.fullScreenState(false, facts(landscape = true))
        assertFalse(landscapeExit.first)
        assertFalse(landscapeExit.second)
    }

    @Test
    fun decisionOnlyReadsPassedFacts() {
        // 纯函数:同输入同输出(旧实现读的是容器实时状态,同样的断言写不出来)
        assertEquals(
            DetailPlaybackCommands.fullScreenState(true, facts(landscape = false)),
            DetailPlaybackCommands.fullScreenState(true, facts(landscape = false)),
        )
    }
}
