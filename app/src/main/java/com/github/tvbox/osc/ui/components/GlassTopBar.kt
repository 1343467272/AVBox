package com.github.tvbox.osc.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.navbar.InteractiveHighlight
import com.github.tvbox.osc.ui.theme.GLASS_AMBIENT_INTENSITY
import com.github.tvbox.osc.ui.theme.GLASS_LIGHT_ANGLE
import com.github.tvbox.osc.ui.theme.GLASS_THICKNESS_ALPHA
import com.github.tvbox.osc.ui.theme.GLASS_THICKNESS_DP
import com.github.tvbox.osc.ui.theme.LiquidGlassState
import com.github.tvbox.osc.ui.theme.REFRACTION_DEPTH_RATIO
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.min

internal val LocalTopBarGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

internal val LocalGlassPauseRecording = compositionLocalOf { { false } }


/** 方向性高光(亮上缘 / 暗下缘):库的 Ambient 样式配正上方光源,是立体感的主来源 */
internal val GlassHighlight: Highlight = Highlight.Ambient.copy(
    style = HighlightStyle.Ambient(
        intensity = GLASS_AMBIENT_INTENSITY,
        angle = GLASS_LIGHT_ANGLE,
    )
)

/** [pressEffect] = false 时不做按压缩放与按压光斑:含输入框的控件必须选这档,见 `SearchField` */
@Composable
fun Modifier.glassTopBarSurface(
    shape: Shape,
    fallbackColor: Color,
    pressEffect: Boolean = true,
): Modifier = glassSurface(LocalTopBarGlassBackdrop.current, shape, fallbackColor, pressEffect)

/** [pressEffect] = false 时不做按压缩放与按压光斑:含输入框的控件必须选这档,见 `SearchField` */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    fallbackColor: Color,
    pressEffect: Boolean = true,
): Modifier = glassSurface(emptyBackdrop(), shape, fallbackColor, pressEffect)

@Composable
private fun Modifier.glassSurface(
    backdrop: Backdrop?,
    shape: Shape,
    fallbackColor: Color,
    pressEffect: Boolean,
): Modifier {
    val config = LiquidGlassState.config
    val glassEnabled = backdrop != null &&
        config.controlsEnabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (!glassEnabled) {
        return this.clip(shape).background(fallbackColor)
    }

    val density = LocalDensity.current
    val blurPx = with(density) { config.blurDp.dp.toPx() }
    val distortionPx = with(density) { config.distortionDp.dp.toPx() }
    val supportsLens = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val isLightTheme = !isSystemInDarkTheme()
    val containerColor = MaterialTheme.colorScheme.surfaceBright.copy(
        alpha = 0.45f * config.containerAlphaScale
    )
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight =
        if (pressEffect) remember(animationScope) { InteractiveHighlight(animationScope) } else null

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(
                brightness = config.contentBrightness,
                contrast = config.contentContrast,
                saturation = 1.5f,
            )
            blur(blurPx)
            if (supportsLens) {
                val refraction = min(distortionPx, size.minDimension / 2f)
                lens(
                    refraction * REFRACTION_DEPTH_RATIO,
                    refraction,
                    depthEffect = true,
                    chromaticAberration = config.dispersion,
                )
            }
        },
        highlight = { GlassHighlight },
        shadow = {
            Shadow(
                radius = 8.dp,
                color = Color.Black.copy(if (isLightTheme) 0.08f else 0.16f),
            )
        },
        innerShadow = {
            InnerShadow(
                radius = GLASS_THICKNESS_DP.dp,
                offset = DpOffset(0.dp, -GLASS_THICKNESS_DP.dp),
                alpha = GLASS_THICKNESS_ALPHA,
            )
        },
        layerBlock = interactiveHighlight?.let(::pressGrowthLayerBlock),
        onDrawSurface = { drawRect(containerColor) },
    )
        .then(interactiveHighlight?.modifier ?: Modifier)
        .then(interactiveHighlight?.gestureModifier ?: Modifier)
}

/** 按压放大:竖向 4dp,横向按最长边算 ⇒ 圆钮两轴同增保持正圆,宽控件只增高、几乎不变宽 */
private fun pressGrowthLayerBlock(
    interactiveHighlight: InteractiveHighlight,
): GraphicsLayerScope.() -> Unit = {
    val progress = interactiveHighlight.pressProgress
    if (progress > 0f && size.height > 0f && size.width > 0f) {
        val growthPx = 4.dp.toPx() * progress
        scaleY = 1f + growthPx / size.height
        scaleX = 1f + growthPx / size.maxDimension
    }
}