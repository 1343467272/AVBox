package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;

import android.util.Base64;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.BoundedCall;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.google.gson.Gson;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;
import com.github.tvbox.osc.util.KV;

import org.json.JSONArray;
import org.json.JSONObject;



import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> actionResult;
    public MutableLiveData<JSONObject> playResult;
    /** 下一集预解析专用通道（预载方案,与 playResult 独立 seq 防串扰,规格 §5.2） */
    public MutableLiveData<JSONObject> preloadResult;

    private Gson gson;
    private final PushDetailResolver pushDetailResolver;
    private final SourceResultParser resultParser;
    private final ListLoader listLoader;
    private final SortLoader sortLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger playRequestSeq = new AtomicInteger();
    private final AtomicInteger preloadRequestSeq = new AtomicInteger();

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        actionResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
        preloadResult = new MutableLiveData<>();
        gson=new Gson();
        pushDetailResolver = new PushDetailResolver(gson, detailResult);
        resultParser = new SourceResultParser(gson, searchResult, detailResult, pushDetailResolver);
        listLoader = new ListLoader(gson, extendCache, listResult, resultParser);
        sortLoader = new SortLoader(gson, extendCache, sortCache, sortResult, listLoader, resultParser);
    }

    /** 站点取数线程池(spider 阻塞调用);池本身在 {@link SourceHelper},这里保留门面入口 */
    public static final ExecutorService spThreadPool = SourceHelper.SPIDER_POOL;

    //homeContent缓存，最多存储5个sourceKey的AbsSortXml对象
    private static final Map<String, AbsSortXml> sortCache = new LinkedHashMap<String, AbsSortXml>(5, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, AbsSortXml> eldest) {
            return size() > 5;
        }
    };

    public static void clearRuntimeCache() {
        sortCache.clear();
        extendCache.clear();
    }

    public void getSort(final String sourceKey) {
        sortLoader.getSort(sourceKey);
    }

    public void getSort(final String sourceKey, final boolean withRec) {
        sortLoader.getSort(sourceKey, withRec);
    }

    public void getList(MovieSort.SortData sortData, int page) {
        listLoader.getList(sortData, page);
    }

    // detailContent
    public void getDetail(String sourceKey, String urlid) {
        getDetail(sourceKey, urlid, false);
    }

    public void getDetail(String sourceKey, String urlid, boolean fallback) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 同 getSort:t0/1/4 的 extend 拉取会阻塞
            final String key = sourceKey;
            final String id = urlid;
            SourceHelper.PREPARE_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    getDetail(key, id, fallback);
                }
            });
            return;
        }
        if (urlid.startsWith("push://") && ApiConfig.get().getSource(PushUrlParser.PUSH_AGENT) != null) {
            String pushUrl = urlid.substring(7);
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    LOG.e("SourceViewModel", e);
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl);
            }
            sourceKey = PushUrlParser.isCastPushUrl(pushUrl) ? PushUrlParser.PUSH_FALLBACK : PushUrlParser.PUSH_AGENT;
            urlid = pushUrl;
        } else if (PushUrlParser.PUSH_AGENT.equals(sourceKey) && PushUrlParser.isCastPushUrl(urlid)) {
            sourceKey = PushUrlParser.PUSH_FALLBACK;
        }
        String id = urlid;
    
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (PushUrlParser.isPushFallback(sourceKey, sourceBean)) {
            detailResult.postValue(createPushDetail(urlid, sourceKey));
            return;
        }
        if (sourceBean == null) {
            // 源已不存在(2026-09-13):典型场景 = 切到新源后加载完成前,从历史记录点进
            // 一条属于旧源(已失效 key)的条目;或订阅源被删。此处返回空 AbsXml(与末尾
            // 未知 type 分支同形状),详情页走空态,而不是在下面 sourceBean.getType() 处 NPE
            LOG.i("echo--getDetail--source-null--" + sourceKey);
            detailResult.postValue(createEmptyDetail(sourceKey));
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    String json = BoundedCall.call(new Callable<String>() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            List<String> ids = new ArrayList<>();
                            ids.add(id);
                            try {
//                                LOG.i("echo--getDetail--id: " + id);
                                return sp.detailContent(ids);
                            } catch (Exception e) {
                                LOG.i("echo--getDetail--error: " + e.getMessage());
                                return "";
                            }
                        }
                    }, fallback ? 6_000L : sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getDetail--" + sourceBean.getKey());
//                    LOG.i("echo--getDetail--result:" + json);
                    resultParser.json(detailResult, json, sourceBean.getKey());
                }
            });
        } else if (type == 0 || type == 1|| type == 4) {
            String extend=sourceBean.getExt();
            extend=fallback ? SourceHelper.getFixUrl(extendCache, gson, extend, 6) : SourceHelper.getFixUrl(extendCache, gson, extend, sourceBean.getPlayTimeoutSeconds());

            GetRequest<String> request = SourceHelper.siteGet(sourceBean)
                    .tag("detail")
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("ids", id);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException(SourceHelper.ERR_NETWORK);
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                resultParser.xml(detailResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                LOG.i(json);
                                resultParser.json(detailResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            resultParser.json(detailResult, "", sourceBean.getKey());
                        }
                    });
        } else {
            detailResult.postValue(createEmptyDetail(sourceKey));
        }
    }

    /** 空详情(源不存在 / 未知 type):详情页按空态渲染,不带任何影片数据 */
    private static AbsXml createEmptyDetail(String sourceKey) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        return data;
    }

    public void action(String sourceKey, String action) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null || action == null) {
            actionResult.postValue(null);
            return;
        }
        if (sourceBean.getType() == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(sourceBean);
                        String json = sp.action(action);
                        actionResult.postValue(TextUtils.isEmpty(json) ? null : new JSONObject(json));
                    } catch (Throwable th) {
                        LOG.e("SourceViewModel", th);
                        actionResult.postValue(null);
                    }
                }
            });
        } else {
            actionResult.postValue(null);
        }
    }

    // searchContent
    public void getSearch(String sourceKey, String wd) {
        getSearch(sourceKey, wd, "");
    }

    public void getSearch(String sourceKey, String wd, String searchToken) {
        getSearch(sourceKey, wd, searchToken, searchResult, "search");
    }

    private void getSearch(String sourceKey, String wd, String searchToken, MutableLiveData<AbsXml> result, String requestTag) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            resultParser.postEmptySearchResult(result, sourceKey, searchToken);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                String search = sp.searchContent(wd, false);
                if(!TextUtils.isEmpty(search)){
                    resultParser.json(result, search, sourceBean.getKey(), searchToken);
                } else {
                    resultParser.json(result, "", sourceBean.getKey(), searchToken);
                }
            } catch (Throwable th) {
                LOG.e("SourceViewModel", th);
                resultParser.json(result, "", sourceBean.getKey(), searchToken);
            }
        } else if (type == 0 || type == 1) {
            SourceHelper.siteGet(sourceBean)
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag(requestTag)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException(SourceHelper.ERR_NETWORK);
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                resultParser.xml(result, xml, sourceBean.getKey(), searchToken);
                            } else {
                                String json = response.body();
                                resultParser.json(result, json, sourceBean.getKey(), searchToken);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            resultParser.postEmptySearchResult(result, sourceBean.getKey(), searchToken);
                        }
                    });
        }else if (type == 4) {
            final String searchWd = wd;
            SourceHelper.PREPARE_POOL.execute(new Runnable() {
                @Override
                public void run() {
            String extend=sourceBean.getExt();
            extend=SourceHelper.getFixUrlDirect(extendCache, gson, extend);
            String queryWd = searchWd;
            try {
                queryWd=URLEncoder.encode(queryWd, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                LOG.e("SourceViewModel", e);
            }

            GetRequest<String> request = SourceHelper.siteGet(sourceBean)
                    .tag(requestTag)
                    .params("wd", queryWd)
                    .params("ac" ,"detail")
                    .params("quick" ,"false");
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            LOG.i("echo-t4 search-网络请求错误");
                            throw new IllegalStateException(SourceHelper.ERR_NETWORK);
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                            String json = response.body();
//                            LOG.i("echo-t4 search onSuccess"+json);
                            resultParser.json(result, json, sourceBean.getKey(), searchToken);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        LOG.i("echo-t4 search-onError");
                        super.onError(response);
                        resultParser.postEmptySearchResult(result, sourceBean.getKey(), searchToken);
                    }
                });
                }
            });
        } else {
            resultParser.postEmptySearchResult(result, sourceBean.getKey(), searchToken);
        }
    }
    // playerContent
    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        getPlayInternal(playRequestSeq, playResult, "play", sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    /** 下一集预解析（预载方案）:结果走 preloadResult 通道,seq 独立于真实播放请求,互不作废 */
    public void getPlayForPreload(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        getPlayInternal(preloadRequestSeq, preloadResult, "playPreload", sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    private void getPlayInternal(AtomicInteger seqHolder, MutableLiveData<JSONObject> resultChannel, String requestTag,
                                 String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        final int requestSeq = seqHolder.incrementAndGet();
        // 取流准备(t4 拉 extend、爬虫调度)可能长时间阻塞:序号先在调用线程占住,
        // 保证随后到来的取消/切集能作废这次请求,实际准备挪到后台
        if (Looper.myLooper() == Looper.getMainLooper()) {
            SourceHelper.PREPARE_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    getPlayPrepared(seqHolder, resultChannel, requestSeq, requestTag, sourceKey, playFlag, progressKey, url, subtitleKey);
                }
            });
            return;
        }
        getPlayPrepared(seqHolder, resultChannel, requestSeq, requestTag, sourceKey, playFlag, progressKey, url, subtitleKey);
    }

    private void getPlayPrepared(AtomicInteger seqHolder, MutableLiveData<JSONObject> resultChannel, int requestSeq, String requestTag,
                                 String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        boolean pushFallback = PushUrlParser.isPushFallback(sourceKey, sourceBean);
        PushUrlParser.PushUrl pushUrl = pushFallback ? PushUrlParser.parsePushUrl(url) : PushUrlParser.createPushUrl(url);
        String requestUrl = pushUrl.url;
        if (pushFallback) {
            postPlayResult(seqHolder, resultChannel, requestSeq, PushUrlParser.createPushPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag));
            return;
        }
        if (sourceBean == null) {
            // 源已不存在(2026-09-13):与 getDetail 同因(切源窗口期/源被删);
            // 走 null 结果 = 取流失败,播放器按"解析失败"处理,不再在下面 NPE
            LOG.i("echo--getPlay--source-null--" + sourceKey);
            postPlayResult(seqHolder, resultChannel, requestSeq, null);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    String json = BoundedCall.call(new Callable<String>() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            if (TextUtils.isEmpty(requestUrl)) return "";
                            try {
                                LOG.i("echo--getPlay--id: " + requestUrl);
                                return sp.playerContent(playFlag, requestUrl, ApiConfig.get().getVipParseFlags());
                            } catch (Exception e) {
                                LOG.i("echo--getPlay--error: " + e.getMessage());
                                return "";
                            }
                        }
                    }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getPlay--" + sourceBean.getKey());
                    LOG.i("echo--getPlay--result:" + json);
                    if (TextUtils.isEmpty(json)) {
                        postPlayResult(seqHolder, resultChannel, requestSeq, null);
                        return;
                    }
                    try {
                        JSONObject result = normalizePlayerResult(new JSONObject(json));
                        result.put("key", url);
                        PushUrlParser.mergePushHeaders(result, pushUrl);
                        mergeSiteHeaders(result, sourceBean);
                        result.put("proKey", progressKey);
                        result.put("subtKey", subtitleKey);
                        if (!result.has("flag"))
                            result.put("flag", playFlag);
                        if (TextUtils.isEmpty(result.optString("url", "")) && shouldDirectPlay(sourceBean, requestUrl)) {
                            postPlayResult(seqHolder, resultChannel, requestSeq, createDirectPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag, sourceBean));
                        } else {
                            postPlayResult(seqHolder, resultChannel, requestSeq, result);
                        }
                    } catch (Exception e) {
                        LOG.i("echo--getPlay--error: " + e.getMessage());
                        postPlayResult(seqHolder, resultChannel, requestSeq, null);
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            JSONObject result = new JSONObject();
            try {
                result.put("key", url);
                String playUrl = sourceBean.getPlayerUrl().trim();
                if (DefaultConfig.isVideoFormat(requestUrl) && playUrl.isEmpty()) {
                    result.put("parse", 0);
                    result.put("url", requestUrl);
                } else {
                    result.put("parse", 1);
                    result.put("url", requestUrl);
                }
                PushUrlParser.mergePushHeaders(result, pushUrl);
                mergeSiteHeaders(result, sourceBean);
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                result.put("playUrl", playUrl);
                result.put("flag", playFlag);
                postPlayResult(seqHolder, resultChannel, requestSeq, result);
            } catch (Throwable th) {
                LOG.e("SourceViewModel", th);
                postPlayResult(seqHolder, resultChannel, requestSeq, null);
            }
        } else if (type == 4) {
            String extend=sourceBean.getExt();
            extend=SourceHelper.getFixUrl(extendCache, gson, extend, sourceBean.getPlayTimeoutSeconds());

            GetRequest<String> request = SourceHelper.siteGet(sourceBean)
                    .tag(requestTag)
                    .params("play", requestUrl)
                    .params("flag" ,playFlag);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            throw new IllegalStateException(SourceHelper.ERR_NETWORK);
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String json = response.body();
                        LOG.i(json);
                        try {
                            JSONObject result = normalizePlayerResult(new JSONObject(json));
                            result.put("key", url);
                            PushUrlParser.mergePushHeaders(result, pushUrl);
                            mergeSiteHeaders(result, sourceBean);
                            result.put("proKey", progressKey);
                            result.put("subtKey", subtitleKey);
                            if (!result.has("flag"))
                                result.put("flag", playFlag);
                            postPlayResult(seqHolder, resultChannel, requestSeq, result);
                        } catch (Throwable th) {
                            LOG.e("SourceViewModel", th);
                            postPlayResult(seqHolder, resultChannel, requestSeq, null);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        postPlayResult(seqHolder, resultChannel, requestSeq, null);
                    }
                });
        }else {
            postPlayResult(seqHolder, resultChannel, requestSeq, null);
        }
    }

    public void cancelPlayRequest() {
        playRequestSeq.incrementAndGet();
    }

    private boolean shouldDirectPlay(SourceBean sourceBean, String requestUrl) {
        return sourceBean != null
                && !TextUtils.isEmpty(requestUrl)
                && (requestUrl.startsWith("http://") || requestUrl.startsWith("https://"));
    }

    private JSONObject createDirectPlayResult(String rawUrl, PushUrlParser.PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag, SourceBean sourceBean) {
        try {
            JSONObject result = new JSONObject();
            result.put("key", rawUrl);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("flag", playFlag);
            result.put("parse", 0);
            result.put("jx", 0);
            result.put("url", pushUrl.url);
            PushUrlParser.mergePushHeaders(result, pushUrl);
            mergeSiteHeaders(result, sourceBean);
            LOG.i("echo--getPlay--direct:" + pushUrl.url);
            return result;
        } catch (Throwable th) {
            LOG.e("SourceViewModel", th);
            return null;
        }
    }

    private JSONObject normalizePlayerResult(JSONObject result) {
        if (result == null) return null;
        try {
            String playUrl = result.optString("playUrl", "");
            String url = result.optString("url", "");
            if (TextUtils.isEmpty(url)) return result;
            if (url.startsWith("[") && url.endsWith("]")) {
                JSONArray array = new JSONArray(url);
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (item instanceof String) {
                        String str = (String) item;
                        if (str.startsWith("proxy://")) {
                            str = DefaultConfig.checkReplaceProxy(str);
                            array.put(i, str);
                        } else if (str.startsWith("video://")) {
                            str = str.substring(8);
                            array.put(i, str);
                        }
                    }
                }
                result.put("url", array.toString());
                result.put("parse", 0);
                return result;
            }
            if (url.startsWith("video://")) {
                url = url.substring(8);
                result.put("url", url);
                result.put("parse", 1);
            } else if (url.startsWith("proxy://")) {
                url = DefaultConfig.checkReplaceProxy(url);
                result.put("url", url);
                result.put("parse", 0);
            } else if (playUrl.length() == 0
                    && DefaultConfig.isVideoFormat(url)
                    && !result.has("parse")
                    && !result.has("jx")) {
                result.put("parse", 0);
            }
        } catch (Throwable th) {
            LOG.e("SourceViewModel", th);
        }
        return result;
    }

    private AbsXml createPushDetail(String url, String sourceKey) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        Movie movie = new Movie();
        movie.videoList = new ArrayList<>();
        Movie.Video video = new Movie.Video();
        video.id = url;
        video.name = url;
        // i18n: keep —— 以下是合成 Movie 的结构化数据(type/flag/`线路名$地址` 格式),会被持久化与比较,不能翻
        video.type = "推送";
        video.sourceKey = sourceKey;
        video.urlBean = new Movie.Video.UrlBean();
        video.urlBean.infoList = new ArrayList<>();
        Movie.Video.UrlBean.UrlInfo urlInfo = new Movie.Video.UrlBean.UrlInfo();
        urlInfo.flag = "推送"; // i18n: keep
        urlInfo.urls = "播放$" + url; // i18n: keep
        urlInfo.beanList = new ArrayList<>();
        urlInfo.beanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean("播放", url)); // i18n: keep
        video.urlBean.infoList.add(urlInfo);
        movie.videoList.add(video);
        data.movie = movie;
        return data;
    }

    /**
     * 站点级 header 作为播放请求的兜底头(fongmi 同语义):只补结果里没有的键,结果自带的头优先。
     * 没配 header 的源这里是空操作。
     */
    private void mergeSiteHeaders(JSONObject result, SourceBean sourceBean) {
        if (result == null || sourceBean == null) return;
        Map<String, String> siteHeader = sourceBean.getHeader();
        if (siteHeader.isEmpty()) return;
        try {
            // 必须先按播放侧的同一口径解析(兼容 header/headers 的对象与 JSON 文本两种形态):
            // 直接看 optJSONObject 会把字符串形态当成"没有头",把源自带的头整块覆盖掉
            HashMap<String, String> merged = PlayerHelper.extractPlayHeaders(result);
            if (merged == null) merged = new HashMap<>();
            for (Map.Entry<String, String> entry : siteHeader.entrySet()) {
                if (!merged.containsKey(entry.getKey())) merged.put(entry.getKey(), entry.getValue());
            }
            JSONObject header = new JSONObject();
            for (Map.Entry<String, String> entry : merged.entrySet()) header.put(entry.getKey(), entry.getValue());
            result.put("header", header);
            // 合并结果统一放 header 一个键,避免 header/headers 两份来源被重复抽取
            result.remove("headers");
        } catch (Throwable th) {
            LOG.e("SourceViewModel", "merge site headers failed", th);
        }
    }

    private void postPlayResult(AtomicInteger seqHolder, MutableLiveData<JSONObject> resultChannel, int requestSeq, JSONObject result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (requestSeq != seqHolder.get()) {
                    LOG.i("echo--getPlay--ignore stale result");
                    return;
                }
                resultChannel.setValue(result);
            }
        });
    }

    private static final ConcurrentHashMap<String, String> extendCache = new ConcurrentHashMap<>();

    @Override
    protected void onCleared() {
        super.onCleared();
    }
}
