package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;

import androidx.lifecycle.MutableLiveData;

import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 站点返回数据的解析与分投:XML/JSON → AbsXml,JSON → AbsSortXml,再按目标通道分投。
 *
 * <p>分投口径是三通道身份判定:搜索通道走 EventBus(面板自行收),
 * 详情通道要过 push/迅雷后处理,其余通道直接 postValue。
 */
final class SourceResultParser {
    private final Gson gson;
    private final MutableLiveData<AbsXml> searchResult;
    private final MutableLiveData<AbsXml> detailResult;
    private final PushDetailResolver pushDetailResolver;

    SourceResultParser(Gson gson, MutableLiveData<AbsXml> searchResult, MutableLiveData<AbsXml> detailResult,
                       PushDetailResolver pushDetailResolver) {
        this.gson = gson;
        this.searchResult = searchResult;
        this.detailResult = detailResult;
        this.pushDetailResolver = pushDetailResolver;
    }

    // XStream 非线程安全:按线程缓存实例复用(勿改共享单例)
    private static final ThreadLocal<XStream> sortXStream = new ThreadLocal<XStream>() {
        @Override
        protected XStream initialValue() {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            return xstream;
        }
    };
    private static final ThreadLocal<XStream> listXStream = new ThreadLocal<XStream>() {
        @Override
        protected XStream initialValue() {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            return xstream;
        }
    };

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        // 2026-09-10 BugFix:必须存 (v → n),与 FilterSheet 消费约定一致(显示 map value=显示名 n,
        // 选中发送 map key=筛选值 v);原 put(n, v) 写反,导致胶囊显示英文 v 且发错筛选值
        for (JsonElement ele : kv) {
            JsonObject ele_obj = ele.getAsJsonObject();
            String values_value = ele_obj.has("v") ? ele_obj.get("v").getAsString() : "";
            String values_name = ele_obj.has("n") ? ele_obj.get("n").getAsString() : "";
            values.put(values_value, values_name);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            if (TextUtils.isEmpty(json)) {
                return new AbsSortJson().toAbsSortXml();
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = gson.fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            sortFilter.add(getSortFilter(one.getAsJsonObject()));
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                sortFilter.add(getSortFilter(ele.getAsJsonObject()));
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    if (data.classes != null && data.classes.sortList != null) {
                        for (MovieSort.SortData sort : data.classes.sortList) {
                            if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                                sort.filters = sortFilters.get(sort.id);
                            }
                        }
                    }
                }
            } catch (Throwable th) {
                LOG.d("SourceViewModel", "sort filters parse failed, continue without filters");
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        try {
            XStream xstream = sortXStream.get();
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            for (MovieSort.SortData sort : data.classes.sortList) {
                if (sort.filters == null) {
                    sort.filters = new ArrayList<>();
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    /** 磁力链接交给迅雷解析改写,结果回投 detailResult */
    public void checkThunder(AbsXml data, int index) {
        pushDetailResolver.checkThunder(data, index);
    }


    AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        return xml(result, xml, sourceKey, "");
    }

    AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey, String searchToken) {
        try {
            XStream xstream = listXStream.get();
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            SourceHelper.absXml(data, sourceKey, searchToken);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                	data = pushDetailResolver.checkPush(data);
                    pushDetailResolver.checkThunder(data,0);
                }else {
                    postSearchResult(result, data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                postEmptySearchResult(result, sourceKey, searchToken);
            } else if (result != null) {
                if (result == detailResult) {
                    AbsXml data = new AbsXml();
                    data.sourceKey = sourceKey;
                    result.postValue(data);
                } else {
                    result.postValue(null);
                }
            }
            return null;
        }
    }

    AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        return json(result, json, sourceKey, "");
    }

    AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey, String searchToken) {
        try {
            AbsJson absJson = gson.fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml data = absJson.toAbsXml();
            SourceHelper.absXml(data, sourceKey, searchToken);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                	data = pushDetailResolver.checkPush(data);
                    pushDetailResolver.checkThunder(data,0);
                }else {
                    postSearchResult(result, data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                postEmptySearchResult(result, sourceKey, searchToken);
            } else if (result != null) {
                if (result == detailResult) {
                    AbsXml data = new AbsXml();
                    data.sourceKey = sourceKey;
                    result.postValue(data);
                } else {
                    result.postValue(null);
                }
            }
            return null;
        }
    }

    void postEmptySearchResult(MutableLiveData<AbsXml> result, String sourceKey, String searchToken) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        data.searchToken = searchToken;
        if (searchResult == result) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
        } else if (result != null) {
            postSearchResult(result, data);
        }
    }

    private void postSearchResult(final MutableLiveData<AbsXml> result, final AbsXml data) {
        result.postValue(data);
    }

}
