package com.github.tvbox.osc.util;

import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.io.Serializable;

public class SettingsUtil {
    public static Object hkGet(String str, Serializable serializable) {
        try {
            return Hawk.get(str, serializable);
        } catch (Exception e) {
            Hawk.delete(str);
            return serializable;
        }
    }
    public static void hkPut(String str, Object obj) {
        try {
            Hawk.put(str, obj);
        } catch (Exception e) {
            LOG.e(e);
        }
    }
    public static void hkDel(String str) {
        try {
            Hawk.delete(str);
        } catch (Exception e) {
            LOG.e(e);
        }
    }
}