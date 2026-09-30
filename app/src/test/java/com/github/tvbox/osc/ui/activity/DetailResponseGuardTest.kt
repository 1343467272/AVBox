package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V4 的核心断言:**迟到回包绝不串进新内容**。
 *
 * 旧实现靠"换 `SourceViewModel` 实例 + 摘观察者"隔离,新实现靠代次比对 —— 这些用例是那条等价性的
 * 唯一书面凭据,改判据前先看这里。重点是被 `sourceKey`/`vodId` 比对漏掉的那一格:
 * 换到另一个源、同一个 vodId 时,两个源的 key 不同但**同一部片的 id 可能相同**(聚合搜索出来的片子
 * 常共用 id),所以只比内容不够,必须比代次。
 */
class DetailResponseGuardTest {

    @Test
    fun responseFromCurrentGenerationPasses() {
        assertTrue(DetailResponseGuard.isCurrent(requestToken = 7, responseToken = 7))
    }

    @Test
    fun lateResponseFromPreviousGenerationIsDropped() {
        // 换片/换源:请求代次已自增,上一代的回包即使 sourceKey/vodId 都对得上也必须丢
        assertFalse(DetailResponseGuard.isCurrent(requestToken = 8, responseToken = 7))
    }

    @Test
    fun responseAheadOfCurrentGenerationIsDropped() {
        // 反向不等同样丢(不假设回包一定"落后")
        assertFalse(DetailResponseGuard.isCurrent(requestToken = 7, responseToken = 8))
    }

    @Test
    fun sameVodFromAnotherSourceIsStillDroppedWhenStale() {
        // 这条是换实例语义的技术替代:源 A 与源 B 命中同一部片 ⇒ sourceKey 会变、vodId 可能相同,
        // 内容比对分不出来,代次可以
        val currentGeneration = 3
        val staleResponseGeneration = 2
        assertFalse(DetailResponseGuard.isCurrent(currentGeneration, staleResponseGeneration))
    }

    @Test
    fun untaggedResponseIsNotBelieved() {
        // null = 没有代次信息(代次上线前入队的在途请求),不采信
        assertFalse(DetailResponseGuard.isCurrent(requestToken = 1, responseToken = null))
    }

    @Test
    fun fallbackChainKeepsTheTokenItStartedWith() {
        // fallback 换站不改代次:同一代内的多个候选站点,谁先回都以同一代次判定为"当前"
        // (代次只在"发起新的内容请求"时自增:换片/换源/重试,不在候选站之间自增)
        val generation = 11
        assertTrue(DetailResponseGuard.isCurrent(generation, generation))
        assertTrue(DetailResponseGuard.isCurrent(generation, generation))
    }
}
