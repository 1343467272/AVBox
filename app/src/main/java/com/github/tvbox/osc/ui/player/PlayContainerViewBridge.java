package com.github.tvbox.osc.ui.player;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Toast;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.PreloadCoordinator;
import com.github.tvbox.osc.player.PlaybackHostApi;
import com.github.tvbox.osc.player.PlaybackViewBridge;
import com.github.tvbox.osc.util.PermissionHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import org.json.JSONObject;
import java.util.HashMap;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

final class PlayContainerViewBridge implements PlaybackViewBridge {

    private final PlayContainer container;

    PlayContainerViewBridge(PlayContainer container) {
        this.container = container;
    }

    @Override
    public boolean isPageAlive() {
        return container.isAttached();
    }

    @Override
    public void runOnUi(Runnable action) {
        if (container.isAttached() && container.mActivity != null) container.mActivity.runOnUiThread(action);
    }

    @Override
    public void toast(CharSequence text) {
        Toast.makeText(container.getContext(), text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void showTip(String msg, boolean loading, boolean error) {
        container.setTip(msg, loading, error);
    }

    @Override
    public void hideTipOnUiThread() {
        container.hideTipOnUiThread();
    }

    @Override
    public int currentPlayState() {
        return container.mVideoView == null ? -1 : container.mVideoView.getCurrentPlayState();
    }

    @Override
    public long currentPosition() {
        return container.mVideoView == null ? 0 : container.mVideoView.getCurrentPosition();
    }

    @Override
    public boolean isPlaying() {
        return container.mVideoView != null && container.mVideoView.isPlaying();
    }

    @Override
    public long duration() {
        return container.mVideoView == null ? 0 : container.mVideoView.getDuration();
    }

    @Override
    public AbstractPlayer mediaPlayer() {
        return container.mVideoView == null ? null : container.mVideoView.getMediaPlayer();
    }

    @Override
    public Context context() {
        return container.getContext();
    }

    @Override
    public PlaybackHostApi playbackHost() {
        return container;
    }

    @Override
    public void requestNotificationPermission() {
        if (container.pageHost != null) {
            container.pageHost.requestNotificationPermission();
        } else if (container.mActivity != null) {
            PermissionHelper.requestNotificationIfNeeded(container.mActivity);
        }
    }

    @Override
    public void switchRenderToTexture() {
        if (container.mVideoView != null && container.mVideoView.isSurfaceRenderActive()) {
            container.mVideoView.switchRenderToTexture();
        }
    }

    @Override
    public void ensureRenderViewMatchesConfig() {
        if (container.mVideoView != null) container.mVideoView.ensureRenderViewMatchesConfig();
    }

    @Override
    public void releasePlayer() {
        container.releasePlayerKernel();
    }

    @Override
    public void setTitle(String title) {
        if (container.mController != null) container.mController.setTitle(title);
    }

    @Override
    public void stopOtherPlayers() {
        if (container.mController != null) container.mController.stopOther();
    }

    @Override
    public void resetDanmu() {
        container.resetDanmuState();
    }

    @Override
    public void startDanmuIfReady() {
        container.startDanmuIfReady();
    }

    @Override
    public void clearLyric() {
        container.clearLyricView();
    }

    @Override
    public void clearArtwork() {
        if (container.mVideoView != null) container.mVideoView.clearArtwork();
    }

    @Override
    public void clearVideoFrame() {
        if (container.mVideoView != null) container.mVideoView.clearVideoFrame();
    }

    @Override
    public void setSubtitleViewVisible(boolean visible) {
        if (container.mController == null) return;
        container.mController.getSubtitleView().setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onNewPlayStarted() {
        container.exitingPreview = false;
    }

    @Override
    public void applyPlayerConfigToView(int forceKernel) {
        if (container.mVideoView == null) return;
        PlayerHelper.updateCfg(container.mVideoView, container.scheduler.playerCfg());
    }

    @Override
    public void useTextureRenderForAudio() {
        if (container.mVideoView != null) container.mVideoView.setRenderViewFactory(TextureRenderViewFactory.create());
    }

    @Override
    public boolean playExternalPlayer(int playerType, String url, String title, String subtitle,
                                     HashMap<String, String> headers, long progress) {
        if (container.mActivity == null) return false;
        return PlayerHelper.runExternalPlayer(playerType, container.mActivity, url, title, subtitle, headers, progress);
    }

    @Override
    public void playM3u8(String url, HashMap<String, String> headers) {
        if (container.mController != null) container.mController.playM3u8(url, headers);
    }

    @Override
    public void playM3u8(String url, HashMap<String, String> headers, int gen) {
        if (!container.scheduler.isParseResultCurrent(gen)) {
            LOG.i("echo-ignore stale m3u8 result");
            return;
        }
        playM3u8(url, headers);
    }

    @Override
    public void startVideoPlayback(String url, HashMap<String, String> headers, boolean forceExoPlayer) {
        if (container.mVideoView == null) return;
        container.mController.hidePauseRoot();
        // EXO 解码方式变更标记(2026-09-17,见 MyVideoView.requireKernelRebuild):复用内核不会重选解码器,
        // 必须走非复用路径先释放再新建;无条件消费一次,避免标记残留到下一次无关起播
        boolean rebuildKernel = container.mVideoView.consumeKernelRebuildRequired();
        boolean reusePlayer = !forceExoPlayer && container.mVideoView.getMediaPlayer() != null && !rebuildKernel;
        if (!reusePlayer) container.hideTip();
        if (!reusePlayer && container.mVideoView.getMediaPlayer() != null) {
            container.releasePlayerKernel();
        }
        container.mVideoView.setProgressKey(container.scheduler.progressKey());
        // 记忆键与进度键同处下发:内核重建后是新实例,起播前必须推给它
        container.mVideoView.setTrackMemoryKey(container.trackMemoryKey());
        container.scheduler.markContentStarted();
        if (headers != null) {
            container.mVideoView.setUrl(url, headers);
        } else {
            container.mVideoView.setUrl(url);
        }
        container.scheduler.startSwitchLinePlayTimeout();
        if (reusePlayer) {
            container.mVideoView.skipPositionWhenPlay((int) container.scheduler.playTimeoutBasePosition());
            container.mVideoView.replay(false);
        } else {
            container.mVideoView.start();
        }
        container.mController.resetSpeed();
    }

    @Override
    public PreloadCoordinator.Snapshot buildPreloadSnapshot() {
        return container.buildPreloadSnapshot();
    }

    @Override
    public void showPreloadReadyTip() {
        container.showPreloadReady();
    }

    @Override
    public void hidePreloadReadyTip() {
        container.hidePreloadReady();
    }

    @Override
    public String firstUrlByArray(String url) {
        return container.mController == null ? url : container.mController.firstUrlByArray(url);
    }

    @Override
    public void setArtwork(String url) {
        if (container.mVideoView != null) container.mVideoView.setArtwork(url);
    }

    @Override
    public void showParse(boolean show) {
        if (container.mController != null) container.mController.showParse(show);
    }

    @Override
    public void checkDanmu(String danmaku, Runnable onFailed) {
        container.checkDanmu(danmaku, onFailed == null ? null : onFailed::run);
    }

    @Override
    public String encodeUrl(String url) {
        return container.mController == null ? url : container.mController.encodeUrl(url);
    }

    @Override
    public void evaluateScript(String url, WebView webView) {
        if (container.mController != null) container.mController.evaluateScript(container.scheduler.sourceBean(), url, webView);
    }

    @Override
    public WebView newSniffWebView() {
        return container.new MyWebView(container.getContext());
    }

    @Override
    public void attachSniffWebView(WebView webView) {
        if (container.isAttached() && container.mActivity != null) {
            container.mActivity.addContentView(webView, new ViewGroup.LayoutParams(1, 1));
        }
    }

    @Override
    public void showErrorWithRetry(String err, boolean finish) {
        container.errorWithRetry(err, finish);
    }

    @Override
    public boolean switchPlayerKernel() {
        return container.mController != null && container.mController.switchPlayer();
    }

    @Override
    public void applyPlayerConfig(JSONObject cfg) {
        if (container.mController != null) container.mController.setPlayerConfig(cfg);
    }

    @Override
    public boolean onLinesExhausted() {
        return container.pageHost != null && container.pageHost.onPlaybackLinesExhausted();
    }
}
