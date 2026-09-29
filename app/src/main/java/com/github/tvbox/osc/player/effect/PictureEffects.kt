package com.github.tvbox.osc.player.effect

import androidx.media3.common.Effect
import com.github.tvbox.osc.player.ExoPlayer
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import java.lang.ref.WeakReference

/** 画质参数不可用的原因(None = 可用;面板据此显示一行说明) */
enum class PictureEffectUnavailableReason {
    None, Tunneling, Hdr, RestartRequired, DecoderUnsupported;

    companion object {
        /** 判定优先级:隧道 > HDR > 本集未开通 > 渲染器吞掉;没有待生效的参数(hasLook=false)一律不提示 */
        fun of(
            tunneling: Boolean,
            hdr: Boolean,
            pipeOpen: Boolean,
            effectsActive: Boolean,
            hasLook: Boolean,
        ): PictureEffectUnavailableReason = when {
            tunneling -> Tunneling
            hdr && hasLook -> Hdr
            !pipeOpen && hasLook -> RestartRequired
            pipeOpen && !effectsActive && hasLook -> DecoderUnsupported
            else -> None
        }
    }
}

/** 画质参数(调色)唯一入口:面板改参数走这里;prepare 前必须下发一次 setVideoEffects,否则本集不生效(media3 只在渲染器首次 enable 时建 VideoSink) */
object PictureEffects {

    /** 效果实例复用:调参只改实例内的 volatile 参数 */
    private val colorTone = ColorToneAdjustEffect()
    private val detail = DetailAdjustEffect()
    private val activeEffects: List<Effect> = listOf(colorTone, detail)

    /** 当前在出画的内核实例(实时调参打给它) */
    private var current: WeakReference<ExoPlayer>? = null

    /** 本集被隧道挡住:不开通、不参与实时调参(参数照常落库,不开隧道起播时生效) */
    private var tunneling = false

    /** 本集是否下发过效果:未启用时不下发 —— 空列表同样会让 media3 建 VideoSink,整段失去直通路径 */
    private var openedThisSession = false

    /** 「按住对比」中:临时按恒等参数出画(不落库) */
    private var comparing = false

    // ==================== 参数读写 ====================

    /** 当前预置(面板 chips 选中态) */
    fun preset(): PicturePreset {
        val name = KV.get(HawkConfig.PICTURE_PRESET, PicturePreset.Original.name)
        return PicturePreset.entries.firstOrNull { it.name == name } ?: PicturePreset.Original
    }

    /** 自定义参数(仅「自定义」预置下参与出画) */
    fun custom(): PictureProfile = PictureProfile(
        saturation = KV.get(HawkConfig.PICTURE_SATURATION, 1f),
        contrast = KV.get(HawkConfig.PICTURE_CONTRAST, 1f),
        brightness = KV.get(HawkConfig.PICTURE_BRIGHTNESS, 0f),
        gamma = KV.get(HawkConfig.PICTURE_GAMMA, 1f),
        hue = KV.get(HawkConfig.PICTURE_HUE, 0f),
        temperature = KV.get(HawkConfig.PICTURE_TEMPERATURE, 0f),
        sharpness = KV.get(HawkConfig.PICTURE_SHARPNESS, 0f),
        shadowLift = KV.get(HawkConfig.PICTURE_SHADOW_LIFT, 0f),
    ).clamped()

    /** 当前应生效的参数(「按住对比」期间恒等) */
    fun applied(): PictureProfile {
        if (comparing) return PictureProfile.OFF
        val preset = preset()
        return if (preset.adjustable) custom() else PictureProfile.of(preset)
    }

    // ==================== 面板入口 ====================

    fun selectPreset(preset: PicturePreset) {
        KV.put(HawkConfig.PICTURE_PRESET, preset.name)
        push()
    }

    fun setCustom(profile: PictureProfile) {
        KV.put(HawkConfig.PICTURE_SATURATION, profile.saturation)
        KV.put(HawkConfig.PICTURE_CONTRAST, profile.contrast)
        KV.put(HawkConfig.PICTURE_BRIGHTNESS, profile.brightness)
        KV.put(HawkConfig.PICTURE_GAMMA, profile.gamma)
        KV.put(HawkConfig.PICTURE_HUE, profile.hue)
        KV.put(HawkConfig.PICTURE_TEMPERATURE, profile.temperature)
        KV.put(HawkConfig.PICTURE_SHARPNESS, profile.sharpness)
        KV.put(HawkConfig.PICTURE_SHADOW_LIFT, profile.shadowLift)
        push()
    }

    /** 恢复默认:预置回「原始」+ 滑条回出厂值 */
    fun reset() {
        KV.put(HawkConfig.PICTURE_PRESET, PicturePreset.Original.name)
        setCustom(PictureProfile.OFF)
    }

    /** 按住对比:true = 临时看原图 */
    fun compare(original: Boolean) {
        if (comparing == original) return
        comparing = original
        push()
    }

    // ==================== 内核入口 ====================

    /**
     * 起播钩子:记下出画内核;隧道与直播都不挂效果链(隧道要帧直出显示面、效果链要帧过 GL 图)。
     * 直播内核每次起播都是新实例(enterLive/enterLiveState 先 releasePlayer),链不会残留;参数照常落库。
     */
    fun onPrepare(player: ExoPlayer, tunnelingBlocked: Boolean) {
        comparing = false
        if (KV.get(HawkConfig.PLAYER_IS_LIVE, false)) {
            current = null
            LOG.i("echo-picture-effects skip: live")
            return
        }
        current = WeakReference(player)
        tunneling = tunnelingBlocked
        if (tunnelingBlocked) {
            LOG.i("echo-picture-effects skip: tunneling enabled")
            return
        }
        val profile = applied()
        colorTone.setProfile(profile)
        detail.setProfile(profile)
        // 链挂着 ⟺ 当前参数非恒等:关闭(参数回恒等)只表示"下次起播不再挂链",绝不下发空列表摘链
        val enabled = !profile.isNoOp
        openedThisSession = enabled
        if (enabled) player.applyVideoEffects(activeEffects)
    }

    /** 内核释放:摘掉引用,后续调参只落库、等下次起播生效 */
    fun onPlayerReleased(player: ExoPlayer) {
        if (current?.get() === player) current = null
    }

    /** 当前不可调色的原因(内核实例缺失时不下结论) */
    fun unavailableReason(): PictureEffectUnavailableReason {
        val player = current?.get() ?: return PictureEffectUnavailableReason.None
        return PictureEffectUnavailableReason.of(
            tunneling = tunneling,
            hdr = player.isPictureHdrSource(),
            pipeOpen = openedThisSession,
            effectsActive = player.isPictureEffectsActive(),
            hasLook = !applied().isNoOp,
        )
    }

    /** 面板改参数后调用:true = 需要重播本集才生效(开=本集还没挂链;关=预置回「原始」且链还挂着);同启用态调参不触发 */
    fun consumeRestartNeeded(): Boolean {
        val player = current?.get() ?: return false
        if (tunneling || player.isPictureHdrSource()) return false
        return restartNeeded(!applied().isNoOp, openedThisSession, preset() == PicturePreset.Original)
    }

    /** 上一条的口径本体(独立出来供 JVM 单测):开=有效果但本集没挂链;关=已回恒等、挂着链且预置是「原始」 */
    internal fun restartNeeded(wantEffects: Boolean, opened: Boolean, presetOriginal: Boolean): Boolean =
        if (wantEffects) !opened else (opened && presetOriginal)

    /** 参数变化:本集已挂效果时只改实例参数(着色器每帧现读,暂停中改的恢复播放后自然生效);本集未挂则先下发一次(链要等重播后的 prepare 才真建) */
    private fun push() {
        val player = current?.get() ?: return
        val profile = applied()
        colorTone.setProfile(profile)
        detail.setProfile(profile)
        if (tunneling) return
        // 已挂链:只改实例参数,不下发任何信令 —— 暂停态重绘(REDRAW)在 media3 未开 replayable cache 时
        // 必抛 UnsupportedOperationException(播放线程),会触发「播放出错」兜底重试并重播本集,勿再加回
        if (openedThisSession) return
        if (profile.isNoOp) return
        player.applyVideoEffects(activeEffects)
    }
}
