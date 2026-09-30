package com.github.tvbox.osc.ui.activity

/**
 * 详情回包的代次守卫(V4)。
 *
 * `DetailViewModel` 原先靠"换 `SourceViewModel` 实例"隔离迟到回包:旧实例上的回包回到已摘除观察者的
 * 通道,天然到不了当前内容。改成单实例 + 流收集后这层隔离消失,必须**显式**判代次 ——
 * 尤其是"换到另一个源、但同一个 vodId"的场景(源 A 与源 B 都能搜到同一部片),此时
 * `sourceKey`/`vodId` 的比对分不出旧回包与新回包,只有代次能分。
 *
 * 规则:回包自带的代次与当前代次不等 ⇒ 丢弃(换片、fallback 换站、重试三条路径共用同一条规则);
 * 不带代次(`null`,代次功能上线前入队的在途请求)同样不采信。
 */
internal object DetailResponseGuard {

    fun isCurrent(requestToken: Int, responseToken: Int?): Boolean =
        responseToken != null && responseToken == requestToken
}
