package com.github.tvbox.osc.sourcedata

import com.github.tvbox.osc.bean.AbsSortXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门面搬出去的运行期状态:`sortCache` 的上限与 access-order 语义、`clearRuntimeCache` 的两条清空。
 *
 * 上限与 access-order 是 2026-09-13(`aa5b132`)定的语义 —— 换源清理和"连 get 都算访问"都靠它,
 * 搬位置时容易只搬字段、把 `removeEldestEntry` 或 access-order 参数丢了。
 */
class SourceRuntimeStateTest {

    private fun put(key: String) {
        synchronized(SourceRuntimeState.sortCache) {
            SourceRuntimeState.sortCache[key] = AbsSortXml()
        }
    }

    private fun get(key: String): AbsSortXml? = synchronized(SourceRuntimeState.sortCache) {
        SourceRuntimeState.sortCache[key]
    }

    @Test
    fun sortCacheKeepsOnlyFiveEntries() {
        SourceRuntimeState.clearRuntimeCache()
        (1..5).forEach { put("src$it") }
        assertEquals(5, SourceRuntimeState.sortCache.size)
        // 第 6 条挤掉最早的一条(插入序 A..E + F ⇒ A 被淘汰)
        put("src6")
        assertEquals(5, SourceRuntimeState.sortCache.size)
        assertNull(get("src1"))
        assertTrue(get("src6") != null)
    }

    @Test
    fun sortCacheGetRefreshesRecency() {
        SourceRuntimeState.clearRuntimeCache()
        (1..5).forEach { put("src$it") }
        // get 是访问:读过 src1 后它变成最近使用,被淘汰的应是 src2
        get("src1")
        put("src6")
        assertTrue(get("src1") != null)
        assertNull(get("src2"))
    }

    @Test
    fun clearRuntimeCacheEmptiesBothCaches() {
        put("src1")
        SourceRuntimeState.extendCache["extend-key"] = "{}"
        SourceRuntimeState.clearRuntimeCache()
        assertEquals(0, SourceRuntimeState.sortCache.size)
        assertEquals(0, SourceRuntimeState.extendCache.size)
    }
}
