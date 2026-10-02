package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** KernelReusePolicy 单测:锁住"这次起播要不要复用内核"唯一判定的真值表 */
class KernelReusePolicyTest {

    private fun decide(
        kernelPresent: Boolean = true,
        rebuildRequired: Boolean = false,
        dedicatedPath: Boolean = false,
        reuseAllowed: Boolean = true,
    ): KernelDecision = KernelReusePolicy.decide(kernelPresent, rebuildRequired, dedicatedPath, reuseAllowed)

    @Test
    fun rebuild_whenKernelMissing() {
        assertEquals(KernelDecision.REBUILD, decide(kernelPresent = false))
    }

    @Test
    fun rebuild_whenRebuildRequiredEvenIfReuseAllowed() {
        assertEquals(KernelDecision.REBUILD, decide(rebuildRequired = true))
    }

    @Test
    fun rebuild_whenDedicatedPathEvenIfReuseAllowed() {
        assertEquals(KernelDecision.REBUILD, decide(dedicatedPath = true))
    }

    @Test
    fun rebuild_whenReuseNotAllowed() {
        assertEquals(KernelDecision.REBUILD, decide(reuseAllowed = false))
    }

    @Test
    fun reuse_onlyWhenAllGatesPass() {
        assertEquals(KernelDecision.REUSE, decide())
    }
}
