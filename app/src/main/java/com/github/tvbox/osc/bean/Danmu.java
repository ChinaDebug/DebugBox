package com.github.tvbox.osc.bean;

import android.text.TextUtils;

import com.github.tvbox.osc.util.LOG;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.Text;
import org.simpleframework.xml.core.Persister;

import java.util.Collections;
import java.util.List;

@Root(name = "i", strict = false)
public class Danmu {

    @ElementList(entry = "d", required = false, inline = true)
    private List<Data> data;

    public static Danmu fromXml(String str) {
        try {
            if (str == null || str.isEmpty()) {
                return new Danmu();
            }
            
            // 修复XML声明中的空格问题
            // 处理 <?xmlversion= 这种缺少空格的情况
            if (str.startsWith("<?xmlversion=")) {
                str = "<?xml version=" + str.substring(12);
            }
            
            // 替换XML数字实体（如 &#128512; 或 &#x1F600;）为空格
            str = str.replaceAll("&#x?[0-9a-fA-F]+;", " ");
            
            // 移除所有非BMP字符（包括代理字符和emoji），但保留换行、回车和Tab
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); ) {
                int codePoint = str.codePointAt(i);
                // 保留Tab(0x09)、换行(0x0A)、回车(0x0D)和BMP范围内的有效字符
                if (codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0D) {
                    sb.appendCodePoint(codePoint);
                } else if (codePoint >= 0x20 && codePoint <= 0xD7FF) {
                    sb.appendCodePoint(codePoint);
                } else if (codePoint >= 0xE000 && codePoint <= 0xFFFD) {
                    sb.appendCodePoint(codePoint);
                }
                i += Character.charCount(codePoint);
            }
            
            return new Persister().read(Danmu.class, sb.toString());
        } catch (Exception e) {
            LOG.e("Danmu", "解析XML失败: " + e.getMessage());
            return new Danmu();
        }
    }

    public List<Data> getData() {
        return data == null ? Collections.emptyList() : data;
    }

    public static class Data {

        @Attribute(name = "p", required = false)
        public String param;

        @Text(required = false)
        public String text;

        public String getParam() {
            return TextUtils.isEmpty(param) ? "" : param;
        }

        public String getText() {
            return TextUtils.isEmpty(text) ? "" : text;
        }
    }
}
