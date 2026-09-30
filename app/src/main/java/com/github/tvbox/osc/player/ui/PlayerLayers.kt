@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.player.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState

/**
 * 浮层组（照搬旧 tv_slide_progress_text / tv_progress_container /
 * loading / tv_play_load_net_speed / tv_back / tv_lock / play_speed_3_container）。
 * 视觉：提示类浮层（seek 提示 / 亮度音量提示 / 长按倍速）统一为**半透明黑药丸 + 白字**
 * —— 4dp 轻投影、无描边、尺寸内容自适应，底色透明度取 [OVERLAY_PILL_ALPHA]（与底栏左下角
 * 那颗时间胶囊同值）。三处位置也统一，见 [HintPillLayer]。
 */

private val PillShape = RoundedCornerShape(50)

@Composable
private fun HintPill(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .shadow(4.dp, PillShape)
            .background(Color.Black.copy(alpha = OVERLAY_PILL_ALPHA), PillShape)
            // 垂直内距 vs_5：胶囊高度主要由内容撑(图标盒/文字行高)，内距只补一点呼吸感 ——
            // 胶囊高度与图标盒一起把 80mm 收到 60mm
            .padding(horizontal = playerDim(R.dimen.vs_20), vertical = playerDim(R.dimen.vs_5)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 提示药丸的统一落点：水平居中 + 屏幕上部（顶部下移 `vs_60`，落在屏幕上方四分之一区域内）。
 * seek / 亮度音量 / 长按倍速三处共用 —— 原先只有 seek 在这里，另两处在屏幕正中，同类提示位置不一
 * （统一到 seek 提示的位置）。
 */
@Composable
private fun HintPillLayer(content: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        HintPill(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = playerDim(R.dimen.vs_60)),
            content = content,
        )
    }
}

/**
 * 加载/错误遮罩：盖住视频面，但**必须**画在顶栏/底栏之前 —— 盖到控制条上时，加载期单击只会
 * 静默翻转 `controlsVisible`（遮罩不拦触摸），用户一个控件也看不到。
 */
@Composable
fun PlayerTipLayer(state: PlayerUiState) {
    if (!state.tipVisible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.tipLoading) {
                ContainedLoadingIndicator(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    indicatorColor = Color.White.copy(alpha = 0.75f),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.icon_error),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(48.dp),
                )
            }
            if (state.tipMsg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.tipMsg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
fun PlayerPauseLayer(state: PlayerUiState, actions: PlayerActions) {
    if (!state.pauseOverlayVisible || state.tipVisible) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CenterControlIcon(
            icon = painterResource(R.drawable.player_ic_play),
            label = stringResource(R.string.common_play),
            onClick = actions::onPlayPauseClicked,
        )
    }
}

/**
 * 亮度/音量提示（替代旧 msg 100/101 + tv_slide_progress_text）。
 * 图标区分调的是哪一项，文本只剩百分比 —— 「亮度」「音量」两词不再出现；
 * 样式与位置见 [HintPillLayer]（半透明黑药丸 + 白字）。
 */
@Composable
fun PlayerSlideHint(state: PlayerUiState) {
    if (!state.slideHintVisible) return
    HintPillLayer {
        Image(
            painter = painterResource(
                if (state.slideHintBrightness) R.drawable.player_ic_brightness
                else R.drawable.player_ic_volume
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(playerDim(R.dimen.vs_50)),
        )
        Spacer(Modifier.width(playerDim(R.dimen.vs_20)))
        Text(
            text = state.slideHintText,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_30),
        )
    }
}

/**
 * seek 提示（快进/快退图标 + 时间，替代 msg 1000/1001）。
 * 图标复用播放参数面板的「设为片头 / 设为片尾」矢量（`|◀` / `▶|`，与快退/快进同向）。
 * 图标盒 `vs_50`：这两颗只占画布约 46%，`vs_50` 盒下图形 ≈23mm、与 `ts_30` 的数字等高
 * （参考图同样是「图标与数字等高」）；盒再大就只是把胶囊顶高（`vs_60` 时胶囊 80mm，现 60mm）。
 */
@Composable
fun PlayerSeekHint(state: PlayerUiState) {
    if (!state.seekHintVisible) return
    HintPillLayer {
        Image(
            painter = painterResource(
                if (state.seekHintForward) R.drawable.player_ic_params_time_end
                else R.drawable.player_ic_params_time_start
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(playerDim(R.dimen.vs_50)),
        )
        Spacer(Modifier.width(playerDim(R.dimen.vs_20)))
        Text(
            text = state.seekHintText,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_30),
        )
    }
}

/** loading（PREPARING/BUFFERING 显示，替代旧 vod_control_loading ProgressBar）；
 *  指示器下方实时网速：复用 1s 轮询刷新的 netSpeedTopRight，
 *  拖动进度条/缓冲时用户可直观看到取流速度 */
@Composable
fun PlayerLoadingLayer(state: PlayerUiState) {
    if (!state.loadingVisible) return
    Box(Modifier.fillMaxSize()) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .size(playerDim(R.dimen.vs_50)),
            color = Color.White,
        )
        Text(
            text = state.netSpeedTopRight,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = playerDim(R.dimen.vs_50) / 2 + playerDim(R.dimen.vs_10)),
        )
    }
}

/** 中央网速（旧 tv_play_load_net_speed：center + marginTop 40mm，仅 IDLE 可见）。
 *  遮罩在屏时不显示：解析期播放态正是 IDLE，网速会压在遮罩上。 */
@Composable
fun PlayerNetSpeedCenter(state: PlayerUiState) {
    if (!state.netSpeedCenterVisible || state.tipVisible) return
    Box(Modifier.fillMaxSize()) {
        Text(
            text = state.netSpeedCenter,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = playerDim(R.dimen.vs_40)),
        )
    }
}

/**
 * 左右两侧各一颗、垂直居中（左：旋转 / 右：锁）。
 * 锁屏三态照搬 showLockView：非预览态非 TV 才出现，锁定 3s 后隐藏。
 * [iconBox] 由 [PlayerOverlay] 统一算出并与动作胶囊共用 ⇒ 两处图标必然等大。
 */
@Composable
fun PlayerSideButtons(state: PlayerUiState, actions: PlayerActions, iconBox: Dp) {
    if (state.lockState == LockVisibility.GONE) return
    val shown = state.lockState == LockVisibility.SHOWN
    // 边距跟随 window 分档（竖屏预览 16dp / 横屏全屏与平板 48dp，见 playerEdgePadding）
    val edge = playerEdgePadding()
    val iconSize = iconBox * ICON_TO_BOX_RATIO
    Box(Modifier.fillMaxSize()) {
        SideButton(
            iconRes = R.drawable.ic_player_rotate,
            contentDescription = stringResource(
                if (state.isPortrait) R.string.player_rotate_landscape else R.string.player_rotate_portrait
            ),
            startSide = true,
            edge = edge,
            iconSize = iconSize,
            // 锁定态隐藏（绕锁旋转无意义）
            visible = shown && !state.locked,
            onClick = actions::onRotateClicked,
        )
        SideButton(
            iconRes = R.drawable.ic_settings_about,
            contentDescription = stringResource(R.string.player_info),
            startSide = false,
            edge = edge,
            iconSize = iconSize,
            visible = shown && !state.locked,
            onClick = actions::onInfoOsdClicked,
            tintWhite = true,
            offsetY = -(iconSize + playerDim(R.dimen.vs_24)),
        )
        SideButton(
            iconRes = if (state.locked) R.drawable.icon_lock else R.drawable.icon_unlock,
            contentDescription = stringResource(R.string.player_lock),
            startSide = false,
            edge = edge,
            iconSize = iconSize,
            visible = shown,
            onClick = actions::onLockClicked,
        )
    }
}

@Composable
private fun BoxScope.SideButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    startSide: Boolean,
    edge: Dp,
    iconSize: Dp,
    visible: Boolean,
    onClick: () -> Unit,
    tintWhite: Boolean = false,
    offsetY: Dp = 0.dp,
) {
    Image(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        alpha = if (visible) 1f else 0f,
        colorFilter = if (tintWhite) ColorFilter.tint(Color.White) else null,
        modifier = Modifier
            .align(if (startSide) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(start = if (startSide) edge else 0.dp, end = if (startSide) 0.dp else edge)
            .offset(y = offsetY)
            .size(iconSize)
            .then(
                if (visible) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onClick() })
                    }
                } else {
                    Modifier
                }
            ),
    )
}

/**
 * 长按倍速浮层（替代 play_speed_3_container / fromLongPress；倍率设置页可调 2x~10x）。
 * 样式与位置见 [HintPillLayer]（半透明黑药丸 + 白字）。
 * 遮罩在屏时不显示：长按倍速作用的是上一次会话的残留内核，提示不该出现在加载画面上。
 */
@Composable
fun PlayerSpeedBoostHint(state: PlayerUiState) {
    if (!state.speedBoostVisible || state.tipVisible) return
    HintPillLayer {
        Text(
            text = "%.1f X".format(state.speedBoostValue),
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_26),
            fontWeight = FontWeight.Bold,
        )
    }
}
