package com.github.tvbox.osc.sourcedata

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * `LiveData` → `Flow` 的唯一桥接点(V4)。
 *
 * 取数侧是 Java、继续用 LiveData(D2 分域决策:重写 9 个文件违反"不重写 Java→Kotlin");页面 VM 是
 * Kotlin,只见 Flow。桥接必须**恰好一处**,否则又会散出一堆手动配对的 `observeForever`/`removeObserver`
 * (V4 之前全库 9 处,漏配对即泄漏)。因此:全库 `observeForever` 只允许出现在本文件。
 *
 * 生命周期:观察者挂在收集协程上 —— `viewModelScope` 取消(VM cleared)即摘除;收集期间始终
 * 有活跃观察者,`LiveData` 的"无观察者不派发"语义也不再影响页面(旧实现挂 observeForever,
 * 等价于永久活跃观察者)。
 */
internal fun <T> LiveData<T>.observeAsFlow(): Flow<T> = callbackFlow {
    val observer = Observer<T> { value -> trySend(value) }
    observeForever(observer)
    awaitClose { removeObserver(observer) }
}
