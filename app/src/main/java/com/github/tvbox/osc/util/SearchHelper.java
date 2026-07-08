package com.github.tvbox.osc.util;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class SearchHelper {

    private static final Gson gson = new Gson();
    private static final Type TYPE_ALL_SOURCES = new TypeToken<HashMap<String, HashMap<String, String>>>() {}.getType();

    private static HashMap<String, HashMap<String, String>> readAll() {
        try {
            String json = Hawk.get(HawkConfig.SOURCES_FOR_SEARCH, "");
            if (json != null && !json.isEmpty()) {
                return gson.fromJson(json, TYPE_ALL_SOURCES);
            }
        } catch (JsonSyntaxException ignored) {
        }
        return new HashMap<>();
    }

    private static void writeAll(HashMap<String, HashMap<String, String>> all) {
        Hawk.put(HawkConfig.SOURCES_FOR_SEARCH, gson.toJson(all));
    }

    private static String getApi() {
        return Hawk.get(HawkConfig.API_URL, HomeActivity.getRes().getString(R.string.app_source));
    }

    public static HashMap<String, String> getSourcesForSearch() {
        String api = getApi();
        if (api.isEmpty()) {
            return null;
        }
        HashMap<String, HashMap<String, String>> all = readAll();
        HashMap<String, String> mCheckSources = all.get(api);
        if (!all.containsKey(api)) {
            mCheckSources = new HashMap<>();
            for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
                if (bean.isSearchable()) {
                    mCheckSources.put(bean.getKey(), "1");
                }
            }
            all.put(api, mCheckSources);
            writeAll(all);
        }
        return mCheckSources != null ? mCheckSources : new HashMap<>();
    }

    public static void putCheckedSources(HashMap<String, String> mCheckSources) {
        String api = getApi();
        if (api.isEmpty()) {
            return;
        }
        HashMap<String, HashMap<String, String>> all = readAll();
        all.put(api, mCheckSources);
        writeAll(all);
    }

    public static List<String> splitWords(String text) {
        List<String> result = new ArrayList<>();
        result.add(text);
        String[] parts = text.split("\\W+");
        if (parts.length > 1) {
            result.addAll(Arrays.asList(parts));
        }
        return result;
    }
}