package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import coil3.transition.Transition

private const val UnnamedPosterKey = "！"

private const val POSTER_CHAR_WIDTH_RATIO = 0.46f

private val PosterPalette = intArrayOf(
    0xFFEF5350.toInt(),
    0xFFEC407A.toInt(),
    0xFFAB47BC.toInt(),
    0xFF7E57C2.toInt(),
    0xFF5C6BC0.toInt(),
    0xFF42A5F5.toInt(),
    0xFF29B6F6.toInt(),
    0xFF26C6DA.toInt(),
    0xFF26A69A.toInt(),
    0xFF66BB6A.toInt(),
    0xFF9CCC65.toInt(),
    0xFFD4E157.toInt(),
    0xFFFFEE58.toInt(),
    0xFFFFCA28.toInt(),
    0xFFFFA726.toInt(),
    0xFFFF7043.toInt(),
    0xFF8D6E63.toInt(),
    0xFFBDBDBD.toInt(),
    0xFF78909C.toInt(),
)

internal fun posterSeedColor(name: String?): Int {
    val key = posterFirstChar(name)
    return PosterPalette[(key.hashCode() and Int.MAX_VALUE) % PosterPalette.size]
}

/** 取单个 UTF-16 码元会把 emoji 切成高位代理,渲染成豆腐块 */
internal fun posterFirstChar(name: String?): String {
    val text = name?.trim().orEmpty()
    if (text.isEmpty()) return UnnamedPosterKey
    return text.take(if (text[0].isHighSurrogate()) 2 else 1)
}

/**
 * 影视海报:有图就画图,无图(源没给 / 加载失败)则落成"片名首字大字"占位。
 *
 * ⚠️ 占位**只在确认没有图时才画**:常驻垫底 + 图片淡入会让每张卡先闪一下色底大字,别再改回去。
 */
@Composable
internal fun VodPoster(
    name: String?,
    pic: String?,
    modifier: Modifier = Modifier,
) {
    // 初值必须"不画":列表项复用时状态会重置回 Empty,画了就是一滚一闪
    var showFallback by remember(pic) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    val request = remember(context, pic) {
        ImageRequest.Builder(context)
            .data(pic)
            // 关掉淡入:淡入期间图片半透明,常驻的垫层会透出来
            .transitionFactory(Transition.Factory.NONE)
            .build()
    }

    Box(modifier = modifier) {
        if (showFallback) PosterFallback(name)
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            onState = { state ->
                showFallback = state is AsyncImagePainter.State.Error
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PosterFallback(name: String?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(posterSeedColor(name))),
        contentAlignment = Alignment.Center,
    ) {
        // 取短边而非宽:Hero 是 1.5 横版,按宽算字号在大屏上顶穿高度
        val charHeight = minOf(maxWidth, maxHeight) * POSTER_CHAR_WIDTH_RATIO
        // 字号按 dp 语义钉在盒子上:写死字面 sp 会再叠一次系统字体缩放,字被放大到出框
        val charSize = with(LocalDensity.current) { charHeight.toSp() }
        Text(
            text = posterFirstChar(name),
            style = TextStyle(
                color = Color.White,
                fontSize = charSize,
                lineHeight = charSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            modifier = Modifier.padding(2.dp),
        )
    }
}
