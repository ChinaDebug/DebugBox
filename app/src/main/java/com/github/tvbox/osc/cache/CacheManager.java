package com.github.tvbox.osc.cache;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
public class CacheManager {
    //反序列,把二进制数据转换成java object对象
    private static Object toObject(byte[] data) {
        ByteArrayInputStream bais = null;
        ObjectInputStream ois = null;
        try {
            bais = new ByteArrayInputStream(data);
            ois = new ObjectInputStream(bais);
            return ois.readObject();
        } catch (Exception e) {
            LOG.e(e);
        } finally {
            try {
                if (bais != null) {
                    bais.close();
                }
                if (ois != null) {
                    ois.close();
                }
            } catch (Exception ignore) {
                LOG.e(ignore);
            }
        }
        return null;
    }

    //序列化存储数据需要转换成二进制
    private static <T> byte[] toByteArray(T body) {
        ByteArrayOutputStream baos = null;
        ObjectOutputStream oos = null;
        try {
            baos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream(baos);
            oos.writeObject(body);
            oos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            LOG.e(e);
        } finally {
            try {
                if (baos != null) {
                    baos.close();
                }
                if (oos != null) {
                    oos.close();
                }
            } catch (Exception e) {
                LOG.e(e);
            }
        }
        return new byte[0];
    }

    public static <T> void delete(String key, T body) {
        Cache cache = new Cache();
        cache.key = key;
        cache.data = toByteArray(body);
        AppDataManager.get().getCacheDao().delete(cache);
    }

    public static <T> void save(String key, T body) {
        Cache cache = new Cache();
        cache.key = key;
        cache.data = toByteArray(body);
        AppDataManager.get().getCacheDao().save(cache);
    }

    public static Object getCache(String key) {
        Cache cache = AppDataManager.get().getCacheDao().getCache(key);
        if (cache != null && cache.data != null) {
            return toObject(cache.data);
        }
        return null;
    }

    public static void deleteAll() {
        AppDataManager.get().getCacheDao().deleteAll();
    }

    public static void deleteByKeyPrefix(String keyPrefix) {
        AppDataManager.get().getCacheDao().deleteByKeyPrefix(keyPrefix);
    }

    public static void deleteVodCache(String sourceKey, VodInfo vodInfo) {
        if (sourceKey == null || vodInfo == null || vodInfo.id == null) {
            return;
        }
        if (vodInfo.seriesMap != null) {
            for (Map.Entry<String, List<VodInfo.VodSeries>> entry : vodInfo.seriesMap.entrySet()) {
                String playFlag = entry.getKey();
                List<VodInfo.VodSeries> seriesList = entry.getValue();
                if (seriesList != null) {
                    for (int i = 0; i < seriesList.size(); i++) {
                        VodInfo.VodSeries vs = seriesList.get(i);
                        if (vs != null) {
                            String progressKey = sourceKey + vodInfo.id + playFlag + vs.getEpisodeId();
                            delete(MD5.string2MD5(progressKey), 0);
                        }
                    }
                }
            }
        }
    }
}
