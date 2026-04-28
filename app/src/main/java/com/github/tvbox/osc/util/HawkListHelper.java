package com.github.tvbox.osc.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class HawkListHelper {
    private static final Gson gson = new Gson();
    private static final Type type = new TypeToken<ArrayList<String>>() {}.getType();
    private static final int MAX_STRING_LENGTH = 2048;

    public static ArrayList<String> getList(String key) {
        if (!Hawk.isBuilt()) {
            return new ArrayList<>();
        }
        String json = Hawk.get(key, "[]");
        try {
            return gson.fromJson(json, type);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void putList(String key, ArrayList<String> list) {
        if (!Hawk.isBuilt()) {
            return;
        }
        if (list == null) {
            list = new ArrayList<>();
        }
        String json = gson.toJson(list);
        Hawk.put(key, json);
    }

    public static void addToList(String key, String value, int maxSize) {
        if (!Hawk.isBuilt()) {
            return;
        }
        if (value == null || value.isEmpty() || value.trim().isEmpty()) {
            return;
        }
        if (value.length() > MAX_STRING_LENGTH) {
            value = value.substring(0, MAX_STRING_LENGTH);
        }
        if (maxSize <= 0) {
            return;
        }
        ArrayList<String> list = getList(key);
        list.remove(value);
        list.add(0, value);
        if (list.size() > maxSize) {
            list.remove(maxSize);
        }
        putList(key, list);
    }

    public static void removeFromList(String key, String value) {
        if (!Hawk.isBuilt()) {
            return;
        }
        if (value == null || value.isEmpty()) {
            return;
        }
        ArrayList<String> list = getList(key);
        list.remove(value);
        putList(key, list);
    }
}
