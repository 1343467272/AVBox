package com.github.tvbox.osc.viewmodel;

import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.github.tvbox.osc.util.BoundedCall;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;

/**
 * 首页取数:站点分类(sort/分类列表)与首页推荐位。
 *
 * <p>带 homeContent 缓存(最多 5 个源),命中的判定与写入条件都在这里;缓存本体由门面持有,
 * 这里只拿引用,便于门面统一清理。
 */
final class SortLoader {
    private final Gson gson;
    private final ConcurrentHashMap<String, String> extendCache;
    private final Map<String, AbsSortXml> sortCache;
    private final MutableLiveData<AbsSortXml> sortResult;
    private final ListLoader listLoader;
    private final SourceResultParser resultParser;

    SortLoader(Gson gson, ConcurrentHashMap<String, String> extendCache, Map<String, AbsSortXml> sortCache,
               MutableLiveData<AbsSortXml> sortResult, ListLoader listLoader, SourceResultParser resultParser) {
        this.gson = gson;
        this.extendCache = extendCache;
        this.sortCache = sortCache;
        this.sortResult = sortResult;
        this.listLoader = listLoader;
        this.resultParser = resultParser;
    }

    private void cacheSort(String sourceKey, AbsSortXml sortXml) {
        attachSortSource(sourceKey, sortXml);
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (!hasHomeRecVideos(sortXml)) {
            return;
        }
        if (!shouldBypassSortCache(sourceKey, sourceBean) && !hasActionSort(sortXml)) {
            sortCache.put(sourceKey, sortXml);
        }
    }

    private static AbsSortXml attachSortSource(String sourceKey, AbsSortXml sortXml) {
        if (sortXml != null) {
            sortXml.sourceKey = sourceKey;
        }
        return sortXml;
    }

    private void postSortResult(String sourceKey, AbsSortXml sortXml) {
        if (sortXml == null) {
            sortXml = new AbsSortXml();
        }
        sortResult.postValue(attachSortSource(sourceKey, sortXml));
    }

    private static boolean hasActionSort(AbsSortXml sortXml) {
        if (sortXml == null) return false;
        if (hasActionVideo(sortXml.videoList)) return true;
        return sortXml.list != null && hasActionVideo(sortXml.list.videoList);
    }

    private static boolean hasHomeRecVideos(AbsSortXml sortXml) {
        return sortXml != null && sortXml.videoList != null && !sortXml.videoList.isEmpty();
    }

    private static boolean hasActionVideo(List<Movie.Video> videos) {
        if (videos == null) return false;
        for (Movie.Video video : videos) {
            if (video != null && video.action != null) return true;
        }
        return false;
    }

    private static boolean shouldBypassSortCache(String sourceKey, SourceBean sourceBean) {
        return SourceHelper.isHomeSource(sourceKey) && SourceHelper.isDoubanSource(sourceBean);
    }

    // homeContent
    void getSort(final String sourceKey) {
        getSort(sourceKey, true);
    }

    /** withRec=false 跳过首页推荐那一次额外请求(豆瓣类 videolist / spider homeVideoContent),sorts 不必等它 */
    void getSort(final String sourceKey, final boolean withRec) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // t4 源要联网拉 extend 才能发 sort 请求,不能占着主线程等它
            SourceHelper.PREPARE_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    getSort(sourceKey, withRec);
                }
            });
            return;
        }
        if (sourceKey == null) {
            sortResult.postValue(new AbsSortXml());
            return;
        }

        // 优先检查缓存
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            LOG.i("echo--getSort-source-null--" + sourceKey);
            postSortResult(sourceKey, null);
            return;
        }
        if(sourceBean.getName().length()<=3 && sourceBean.getName().endsWith("搜")){ // i18n: keep
            postSortResult(sourceKey, null);
            return;
        }

        if (!shouldBypassSortCache(sourceKey, sourceBean)) {
            AbsSortXml cached = sortCache.get(sourceKey);
            if (cached != null) {
                boolean shouldUseCache = cached.videoList != null && !cached.videoList.isEmpty();
                if (shouldUseCache) {
                    attachSortSource(sourceKey, cached);
                    postSortResult(sourceKey, cached);
                    return;
                }
            }
        }

        final int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    String sortJson = BoundedCall.call(new Callable<String>() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            String json = sp.homeContent(true);
//                            LOG.i("echo--getSort :" + json);
                            return json;
                        }
                    }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getSort--" + sourceBean.getKey());
                    if (sortJson != null) {
                        final AbsSortXml sortXml = resultParser.sortJson(sortResult, sortJson);
                        attachSortSource(sourceKey, sortXml);
                        if (sortXml != null) {
                            AbsXml absXml = resultParser.json(null, sortJson, sourceBean.getKey());
                            if (!withRec) {
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            } else if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                sortXml.videoList = absXml.movie.videoList;
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            } else {
                                listLoader.getHomeRecList(sourceBean, null, new ListLoader.HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        sortXml.videoList = videos;
                                        postSortResult(sourceKey, sortXml);
                                        cacheSort(sourceKey, sortXml);
                                    }
                                });
                            }
                        } else {
                            postSortResult(sourceKey, sortXml);
                            cacheSort(sourceKey, sortXml);
                        }
                    } else {
                        postSortResult(sourceKey, null);
                    }
                }
            };
            SourceHelper.PREPARE_POOL.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            SourceHelper.siteGet(sourceBean)
                    .tag(sourceBean.getKey() + "_sort")
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
                            AbsSortXml sortXml = null;
                            if (type == 0) {
                                String xml = response.body();
                                sortXml = resultParser.sortXml(sortResult, xml);
                            } else if (type == 1) {
                                String json = response.body();
                                sortXml = resultParser.sortJson(sortResult, json);
                            }
                            attachSortSource(sourceKey, sortXml);
                            if (withRec && sortXml != null && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                final AbsSortXml finalSortXml = sortXml;
                                listLoader.getHomeRecList(sourceBean, ids, new ListLoader.HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        finalSortXml.videoList = videos;
                                        postSortResult(sourceKey, finalSortXml);
                                        cacheSort(sourceKey, finalSortXml);
                                    }
                                });
                            } else {
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            postSortResult(sourceKey, null);
                        }
                    });
        }else if (type == 4) {
            String extend=sourceBean.getExt();
            extend=SourceHelper.getFixUrl(extendCache, gson, extend, sourceBean.getPlayTimeoutSeconds());
            if(URLEncoder.encode(extend).length()<1000){
                GetRequest<String> request = SourceHelper.siteGet(sourceBean)
                        .tag(sourceBean.getKey() + "_sort")
                        .params("filter", "true");
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
                                String sortJson  = response.body();
                                if (sortJson != null) {
                                    final AbsSortXml sortXml = resultParser.sortJson(sortResult, sortJson);
                                    attachSortSource(sourceKey, sortXml);
                                    if (sortXml != null) {
                                        AbsXml absXml = resultParser.json(null, sortJson, sourceBean.getKey());
                                        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                            sortXml.videoList = absXml.movie.videoList;
                                            postSortResult(sourceKey, sortXml);
                                            cacheSort(sourceKey, sortXml);
                                        } else {
                                            listLoader.getHomeRecList(sourceBean, null, new ListLoader.HomeRecCallback() {
                                                @Override
                                                public void done(List<Movie.Video> videos) {
                                                    sortXml.videoList = videos;
                                                    postSortResult(sourceKey, sortXml);
                                                    cacheSort(sourceKey, sortXml);
                                                }
                                            });
                                        }
                                    } else {
                                        postSortResult(sourceKey, sortXml);
                                        cacheSort(sourceKey, sortXml);
                                    }
                                } else {
                                    postSortResult(sourceKey, null);
                                }
                            }

                            @Override
                            public void onError(Response<String> response) {
                                super.onError(response);
                                postSortResult(sourceKey, null);
                            }
                        });
            }else {
                try {
                    Map<String, String> params = new HashMap<>();
                    params.put("filter","true");
                    if (extend != null && !extend.isEmpty()) {
                        params.put("extend",extend);
                    }
                    RemoteTVBox.post(sourceBean.getApi(), params, sourceBean.getHeader(), new okhttp3.Callback() {
                        @Override
                        public void onFailure(@NonNull Call call, IOException e) {
                            postSortResult(sourceKey, null);
                        }

                        @Override
                        public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
                            assert response.body() != null;
                            String sortJson = response.body().string();
                            final AbsSortXml sortXml = resultParser.sortJson(sortResult, sortJson);
                            attachSortSource(sourceKey, sortXml);
                            if (sortXml != null) {
                                AbsXml absXml = resultParser.json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    postSortResult(sourceKey, sortXml);
                                    cacheSort(sourceKey, sortXml);
                                }
                            } else {
                                postSortResult(sourceKey, sortXml);
                                cacheSort(sourceKey, sortXml);
                            }
                        }
                    });
                } catch (Exception ignored) {
                    postSortResult(sourceKey, null);
                }
            }
        } else {
            postSortResult(sourceKey, null);
        }
    }
}
