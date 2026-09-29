package com.github.tvbox.osc.util;

import com.github.tvbox.osc.util.LOG;
import android.app.Activity;
import android.content.Context;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.player.ExoMediaPlayerFactory;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.render.SurfaceRenderViewFactory;
import com.github.tvbox.osc.player.thirdparty.Kodi;
import com.github.tvbox.osc.player.thirdparty.MXPlayer;
import com.github.tvbox.osc.player.thirdparty.ReexPlayer;
import com.github.tvbox.osc.player.thirdparty.VlcPlayer;
import com.github.tvbox.osc.util.KV;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayerHelper {
    public static void updateCfg(VideoView videoView, JSONObject playerCfg) {
        updateCfg(videoView,playerCfg,-1);
    }

    /** forcePlayerType 为历史遗留(内核对仅剩 EXO,不再有可强制的目标),保留入参以稳定既有调用方 */
    public static void updateCfg(VideoView videoView, JSONObject playerCfg,int forcePlayerType) {
        int renderType = KV.get(HawkConfig.PLAY_RENDER, 1);
        String exoDecode = KV.get(HawkConfig.EXO_DECODE, "硬解码"); // i18n: keep
        int scale = KV.get(HawkConfig.PLAY_SCALE, 0);
        try {
            renderType = playerCfg.getInt("pr");
            scale = playerCfg.getInt("sc");
        } catch (JSONException e) {
            LOG.e("PlayerHelper", e);
        }
        // exo 键单独用 optString 读(2026-09-17):不塞进上面的 try —— 该 try 遇第一个缺失键即中断,
        // 老播放记录/直播配置没有 exo 键时会把后面的 sc 一起吞掉
        exoDecode = playerCfg.optString("exo", exoDecode);
        // EXO 解码方式下发(2026-09-17):进程级静态位,与 videoView 实例无关(故不放在下面的判空块里),
        // 每次起播前按"本剧配置 → 全局设置"的有效值推一次
        boolean exoDecodeChanged = applyExoDecode(exoDecode);
        PlayerFactory playerFactory = ExoMediaPlayerFactory.create();
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        if(videoView!=null){
            videoView.setPlayerFactory(playerFactory);
            if (videoView instanceof MyVideoView) {
                // EXO 解码方式变了且当前还活着一个 EXO 内核(换集复用路径):media3 不会重选解码器,
                // 只改静态位不生效 —— 标记本次起播必须重建内核(见 MyVideoView.consumeKernelRebuildRequired)
                if (exoDecodeChanged && ((MyVideoView) videoView).getMediaPlayer() instanceof ExoPlayer) {
                    ((MyVideoView) videoView).requireKernelRebuild();
                    LOG.i("echo-exo-decode-changed: rebuild kernel on next start");
                }
            }
            videoView.setRenderViewFactory(renderViewFactory);
            videoView.setScreenScaleType(scale);
        }
    }

    /**
     * 下发 EXO 解码方式(2026-09-17)。
     *
     * <p>EXO 没有"构造时固化"的 options,软/硬解由 media3 的 {@code MediaCodecSelector} 决定,
     * 而选择器只在**解码器新建**时才被查询 —— 故下发本身只写一个进程级静态位,不需要拿播放器实例。
     * 但内核复用时 media3 可能继续沿用旧的 MediaCodec(renderer disable 只 flush 不 release),
     * 光改静态位对本次复用无效 —— 故返回 true 时由调用方补一个"必须重建内核"标记兜住。
     *
     * @return 本次下发是否**改变了**解码方式(调用方据此判断复用中的内核要不要重建,见 updateCfg)
     */
    private static boolean applyExoDecode(String exoDecode) {
        boolean prefer = "软解码".equals(exoDecode); // i18n: keep
        if (ExoPlayer.isPreferSoftwareDecode() == prefer) return false;
        ExoPlayer.setPreferSoftwareDecode(prefer);
        return true;
    }

    /**
     * 本地代理 URL 判定(2026-09-13):spider 自建代理(网盘)/M3U8 净化/DASH 代理都是
     * 127.0.0.1 上 App 内服务的地址,不是稳定的可随机访问 HTTP 文件源。
     * 边播缓存的 CacheDataSource 与这类 URL 的区间读取语义不兼容 —— 实测夸克 4K mp4 源
     * 需跳读文件尾 moov 时抛 ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,EXO 直接无法起播
     * (关掉边播缓存即恢复正常);直连 URL(可随机访问)不受影响。
     * 故这类 URL 跳过磁盘缓存,与预载侧 PreloadCoordinator 的排除口径一致。
     */
    public static boolean isLocalProxyUrl(String url) {
        if (url == null) return false;
        return url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1")
                || url.startsWith("http://localhost") || url.startsWith("https://localhost");
    }

    /**
     * 从 getPlay 结果 JSON 提取请求头(header/headers 字段,兼容 JSONObject 与 JSON 文本两种形态)。
     *
     * <p>2026-09-13 修复:预载({@code PreloadCoordinator.extractHeaders})与播放
     * ({@code PlayContainer.getHeaders})必须共用本方法 —— 此前预载侧只认 JSONObject、
     * 播放侧还认 String,源返回 {@code "header":"{\"User-Agent\":\"...\"}"} 时两侧的
     * {@code keyOf(url,headers)} 不一致,预载内存数据永不命中(仅剩磁盘兜底)。
     *
     * @return 提取到的请求头(键值均原样保留,不 trim);无任何头时返回 null(与旧实现语义一致)
     */
    public static HashMap<String, String> extractPlayHeaders(JSONObject playResult) {
        if (playResult == null) return null;
        HashMap<String, String> headers = new HashMap<>();
        appendJsonHeaders(headers, playResult.opt("header"));
        appendJsonHeaders(headers, playResult.opt("headers"));
        return headers.isEmpty() ? null : headers;
    }

    /** 合并单个 header(s) 字段:接受 JSONObject 或 JSON 文本;非法内容静默跳过(保持旧行为) */
    public static void appendJsonHeaders(HashMap<String, String> headers, Object rawHeaders) {
        if (headers == null || rawHeaders == null || rawHeaders == JSONObject.NULL) return;
        try {
            JSONObject json = null;
            if (rawHeaders instanceof JSONObject) {
                json = (JSONObject) rawHeaders;
            } else if (rawHeaders instanceof String) {
                String text = ((String) rawHeaders).trim();
                if (!TextUtils.isEmpty(text)) {
                    json = new JSONObject(text);
                }
            }
            if (json == null) return;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!TextUtils.isEmpty(key)) {
                    headers.put(key, json.optString(key, ""));
                }
            }
        } catch (Throwable th) {
            LOG.e("PlayerHelper", "play headers parse failed", th);
        }
    }

    /** 播放器名;每次调用重取文案(不缓存字符串 —— 缓存会让切语言后停在旧语言) */
    public static String getPlayerName(int playType) {
        switch (playType) {
            case 10:
                return str(R.string.player_mx);
            case 11:
                return str(R.string.player_reex);
            case 12:
                return str(R.string.player_kodi);
            case 13:
                return str(R.string.player_nearby_tvbox);
            case 14:
                return str(R.string.player_vlc);
            default:
                return str(R.string.player_exo);
        }
    }

    public static HashMap<Integer, String> getPlayersInfo() {
        HashMap<Integer, String> playersInfo = new HashMap<>();
        for (int type : new int[]{2, 10, 11, 12, 13, 14}) {
            playersInfo.put(type, getPlayerName(type));
        }
        return playersInfo;
    }

    private static HashMap<Integer, Boolean> mPlayersExistInfo = null;

    /**
     * 作废"可用播放器"缓存(2026-09-13)。
     * ⚠️ 该表是**进程级缓存**(首次调用后不再重算),而 13 号 RemoteTVBox 的可用性取决于
     * `HawkConfig.REMOTE_TVBOX` —— 投屏扫描/投屏成功时才写入。不重置缓存的话,
     * 「RemoteTVBox 播放器」选项在本次进程内永远不会出现。
     */
    public static void invalidatePlayersExistInfo() {
        mPlayersExistInfo = null;
    }

    public static HashMap<Integer, Boolean> getPlayersExistInfo() {
        if (mPlayersExistInfo == null) {
            HashMap<Integer, Boolean> playersExist = new HashMap<>();
            playersExist.put(2, true);
            playersExist.put(10, MXPlayer.getPackageInfo() != null);
            playersExist.put(11, ReexPlayer.getPackageInfo() != null);
            playersExist.put(12, Kodi.getPackageInfo() != null);
            playersExist.put(13, RemoteTVBox.getAvalible() != null);
            playersExist.put(14, VlcPlayer.getPackageInfo() != null);
            mPlayersExistInfo = playersExist;
        }
        return mPlayersExistInfo;
    }

    public static Boolean getPlayerExist(int playType) {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo.get(playType);
        } else {
            return false;
        }
    }

    public static ArrayList<Integer> getExistPlayerTypes() {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        ArrayList<Integer> existPlayers = new ArrayList<>();
        for(Integer playerType : playersExistInfo.keySet()) {
            if (playersExistInfo.get(playerType)) {
                existPlayers.add(playerType);
            }
        }
        return existPlayers;
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers) {
        return runExternalPlayer(playerType, activity, url, title, subtitle, headers);
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers, long progress) {
        boolean callResult = false;
        switch (playerType) {
            case 10: {
                callResult = MXPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 11: {
                callResult = ReexPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 12: {
                callResult = Kodi.run(activity, url, title, subtitle, headers);
                break;
            }
            case 13: {
                callResult = RemoteTVBox.run(activity, url, title, subtitle, headers);
                break;
            }
            case 14: {
                callResult = VlcPlayer.run(activity, url, title, subtitle, progress);
                break;
            }
        }
        return callResult;
    }

    public static String getRenderName(int renderType) {
        if (renderType == 1) {
            return "SurfaceView";
        } else {
            return "TextureView";
        }
    }

    /** 画面缩放名;每次调用重取文案(不缓存字符串 —— 缓存会让切语言后停在旧语言) */
    public static String getScaleName(int screenScaleType) {
        switch (screenScaleType) {
            case VideoView.SCREEN_SCALE_16_9:
                return "16:9";
            case VideoView.SCREEN_SCALE_4_3:
                return "4:3";
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                return str(R.string.player_scale_fill);
            case VideoView.SCREEN_SCALE_ORIGINAL:
                return str(R.string.player_scale_origin);
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                return str(R.string.player_scale_crop);
            default:
                return str(R.string.common_default);
        }
    }

    /**
     * 资源文案;App 未就绪(极早调用/单测)返回空串,不抛异常。
     * 走 {@link LanguageManager#localized}:Application 的 base 只在进程启动时挂一次,切语言后
     * 直接用 app.getString 会停在旧语言。
     */
    private static String str(int resId) {
        Context app = AppContextHolder.context();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId);
    }

    /** 网速文本:入参是字节/秒,按 1024 进制显示 B/s / KB/s / MB/s;show=false 时 0 返回空串 */
    public static String getDisplaySpeed(long speed,boolean show) {
        if(speed > 1048576)
            return new DecimalFormat("#.00").format(speed / 1048576d) + "MB/s";
        else if(speed > 1024)
            return (speed / 1024) + "KB/s";
        else
            return speed > 0?speed + "B/s":(show?"0B/s":"");
    }
}
