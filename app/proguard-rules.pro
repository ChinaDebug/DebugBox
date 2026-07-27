#############################################
# 基础优化配置
#############################################
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-dontpreverify
-verbose
-optimizations !code/simplification/cast,!field/*,!class/merging/*
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,LineNumberTable,SourceFile,Exceptions
-renamesourcefileattribute SourceFile

# 将包里的类混淆后重新打包到一个统一的package中（避免使用 androidx 等真实存在的包名）
-repackageclasses o.a

#############################################
# Android 基础保留
#############################################
# 保留四大组件及Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class * extends android.view.View

# 保留本地native方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留Activity中onClick方法
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# 保留枚举类
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留Parcelable序列化类
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留Serializable序列化类的关键成员
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
    <fields>;
}

# 保留自定义View
-keep public class * extends android.view.View {
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 保留RecyclerView LayoutManager
-keep public class * extends androidx.recyclerview.widget.RecyclerView$LayoutManager {
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 保留R类
-keep class **.R$* { *; }

# 保留回调函数onXXEvent、**On*Listener
-keepclassmembers class * {
    void *(**On*Event);
    void *(**On*Listener);
}

#############################################
# Kotlin & 注解支持
#############################################
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# androidx.annotation.Keep 注解（项目中有大量 @Keep）
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers @androidx.annotation.Keep class * { *; }

#############################################
# DataBinding
#############################################
-keep class androidx.databinding.** { *; }
-dontwarn androidx.databinding.**
-keep class * extends androidx.databinding.DataBinderMapper { *; }
-keep class * extends androidx.databinding.ViewDataBinding { *; }
-keep class com.github.tvbox.osc.BR { *; }

#############################################
# EventBus
#############################################
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
-keepclassmembers class * extends org.greenrobot.eventbus.util.ThrowableFailureEvent {
    <init>(java.lang.Throwable);
}

#############################################
# WebView JavascriptInterface
#############################################
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

#############################################
# Room 数据库
#############################################
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers @androidx.room.Entity class * {
    @androidx.room.PrimaryKey <fields>;
    @androidx.room.ColumnInfo <fields>;
    @androidx.room.Embedded <fields>;
}
-keepclassmembers class * {
    @androidx.room.Query <methods>;
    @androidx.room.Insert <methods>;
    @androidx.room.Update <methods>;
    @androidx.room.Delete <methods>;
}
-dontwarn androidx.room.paging.**

#############################################
# Gson 序列化
#############################################
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
  @com.google.gson.annotations.Expose <fields>;
}

#############################################
# XStream 序列化
#############################################
-keep class com.thoughtworks.xstream.** { *; }
-keepclassmembers class * {
    @com.thoughtworks.xstream.annotations.XStreamAlias <fields>;
    @com.thoughtworks.xstream.annotations.XStreamAsAttribute <fields>;
    @com.thoughtworks.xstream.annotations.XStreamImplicit <fields>;
    @com.thoughtworks.xstream.annotations.XStreamConverter <fields>;
    @com.thoughtworks.xstream.annotations.XStreamOmitField <fields>;
}
-keep @com.thoughtworks.xstream.annotations.XStreamAlias class * { *; }

#############################################
# SimpleXML
#############################################
-keep class org.simpleframework.xml.** { *; }
-dontwarn org.simpleframework.xml.**
-keepclassmembers,allowobfuscation class * {
    @org.simpleframework.xml.Path <fields>;
    @org.simpleframework.xml.Root <fields>;
    @org.simpleframework.xml.Text <fields>;
    @org.simpleframework.xml.Element <fields>;
    @org.simpleframework.xml.Attribute <fields>;
    @org.simpleframework.xml.ElementList <fields>;
}

#############################################
# 第三方库
#############################################
# OkHttp / Okio
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.GeneratedAppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Hawk
-keep class com.orhanobut.hawk.** { *; }

# LoadSir
-dontwarn com.kingja.loadsir.**
-keep class com.kingja.loadsir.** { *; }

# dkplayer
-keep class com.dueeeke.videoplayer.** { *; }
-dontwarn com.dueeeke.videoplayer.**

# IjkPlayer
-keep class tv.danmaku.ijk.** { *; }
-dontwarn tv.danmaku.ijk.**

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Sardine WebDAV
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-dontwarn com.thegrizzlylabs.sardineandroid.**

# FilePicker
-keep class com.obsez.android.lib.filechooser.** { *; }
-dontwarn com.obsez.android.lib.filechooser.**

# jcifs (SMB)
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# zxing
-keep class com.google.zxing.** { *; }

# jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# NanoHttpd
-keep class fi.iki.elonen.** { *; }

# xmlpull
-keep class org.xmlpull.v1.** { *; }

# Conscrypt
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.psse.SSLParametersImpl
-dontwarn org.conscrypt.**

# OkGo
-keep class com.lzy.okgo.** { *; }
-dontwarn com.lzy.okgo.**

# XXPermissions（28.3 版本 AAR 已自带 consumer ProGuard 规则，此处再做一层兜底保留）
-keep class com.hjq.permissions.** { *; }
-keep interface com.hjq.permissions.** { *; }
-keepclassmembers class com.hjq.permissions.** { *; }
-dontwarn com.hjq.permissions.**

#############################################
# TVBox 业务核心（必须保留）
#############################################
# Bean 包：涉及 Gson/XStream/Serializable 序列化，必须保留类名、字段名和方法名
-keep class com.github.tvbox.osc.bean.** { *; }
-keep class com.github.tvbox.osc.ui.fragment.homes.** { *; }
-keep class com.github.tvbox.osc.ui.tv.activity.** { *; }
-keep class com.github.tvbox.osc.ui.tv.adapter.** { *; }
-keep class com.github.tvbox.osc.ui.tv.widget.** { *; }

# 爬虫相关：动态加载的类不能被混淆
-keep interface com.github.catvod.crawler.Spider { *; }
-keep class * implements com.github.catvod.crawler.Spider {
    <init>();
    public <methods>;
}
-keep class com.github.catvod.crawler.* { *; }
-keep class com.github.catvod.spider.Init {
    public static void init(android.content.Context);
}
-keep class com.github.catvod.spider.Proxy {
    public static java.lang.String proxy(java.util.Map);
}

# JS 桥接：Global / local 类通过反射向 QuickJS 暴露方法
-keep class com.github.tvbox.osc.util.js.** { *; }
-keep class com.whl.quickjs.wrapper.** { *; }

# 其他核心业务包（Adapter/ViewModel/Server/Receiver/Cast/Subtitle/Player）
-keep class com.github.tvbox.osc.ui.adapter.** { *; }
-keep class com.github.tvbox.osc.ui.tv.adapter.** { *; }
-keep class com.github.tvbox.osc.viewmodel.** { *; }
-keep class com.github.tvbox.osc.server.** { *; }
-keep class com.github.tvbox.osc.receiver.** { *; }
-keep class com.github.tvbox.osc.cast.** { *; }
-keep class com.github.tvbox.osc.subtitle.** { *; }
-keep class com.github.tvbox.osc.player.** { *; }

# Python 支持
-keep public class com.undcover.freedom.pyramid.** { *; }
-dontwarn com.undcover.freedom.pyramid.**
-keep public class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# 迅雷下载库
-keep class com.xunlei.downloadlib.** { *; }
-dontwarn com.xunlei.downloadlib.**

# QuickJS 引擎
-keep class com.whl.quickjs.** { *; }

#############################################
# 保留重要工具类（避免反射或序列化失败）
#############################################
-keep class com.github.tvbox.osc.util.DefaultConfig { *; }
-keep class com.github.tvbox.osc.util.HawkConfig { *; }
-keep class com.github.tvbox.osc.util.LOG { *; }
-keep class com.github.tvbox.osc.util.MD5 { *; }

#############################################
#  suppress warnings（AGP/R8 自动生成，按需补充）
#############################################
-dontwarn com.ctc.wstx.stax.WstxInputFactory
-dontwarn com.ctc.wstx.stax.WstxOutputFactory
-dontwarn java.awt.Color
-dontwarn java.awt.Font
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.PropertyEditor
-dontwarn javax.activation.ActivationDataFlavor
-dontwarn javax.swing.plaf.FontUIResource
-dontwarn javax.xml.bind.DatatypeConverter
-dontwarn net.sf.cglib.proxy.Callback
-dontwarn net.sf.cglib.proxy.CallbackFilter
-dontwarn net.sf.cglib.proxy.Enhancer
-dontwarn net.sf.cglib.proxy.Factory
-dontwarn net.sf.cglib.proxy.NoOp
-dontwarn net.sf.cglib.proxy.Proxy
-dontwarn nu.xom.Attribute
-dontwarn nu.xom.Builder
-dontwarn nu.xom.Document
-dontwarn nu.xom.Element
-dontwarn nu.xom.Elements
-dontwarn nu.xom.Node
-dontwarn nu.xom.ParentNode
-dontwarn nu.xom.ParsingException
-dontwarn nu.xom.Text
-dontwarn nu.xom.ValidityException
-dontwarn org.codehaus.jettison.AbstractXMLStreamWriter
-dontwarn org.codehaus.jettison.mapped.Configuration
-dontwarn org.codehaus.jettison.mapped.MappedNamespaceConvention
-dontwarn org.codehaus.jettison.mapped.MappedXMLInputFactory
-dontwarn org.codehaus.jettison.mapped.MappedXMLOutputFactory
-dontwarn org.dom4j.Attribute
-dontwarn org.dom4j.Branch
-dontwarn org.dom4j.Document
-dontwarn org.dom4j.DocumentException
-dontwarn org.dom4j.DocumentFactory
-dontwarn org.dom4j.Element
-dontwarn org.dom4j.io.OutputFormat
-dontwarn org.dom4j.io.SAXReader
-dontwarn org.dom4j.io.XMLWriter
-dontwarn org.dom4j.tree.DefaultElement
-dontwarn org.jdom.Attribute
-dontwarn org.jdom.Content
-dontwarn org.jdom.DefaultJDOMFactory
-dontwarn org.jdom.Document
-dontwarn org.jdom.Element
-dontwarn org.jdom.JDOMException
-dontwarn org.jdom.JDOMFactory
-dontwarn org.jdom.Text
-dontwarn org.jdom.input.SAXBuilder
-dontwarn org.jdom2.Attribute
-dontwarn org.jdom2.Content
-dontwarn org.jdom2.DefaultJDOMFactory
-dontwarn org.jdom2.Document
-dontwarn org.jdom2.Element
-dontwarn org.jdom2.JDOMException
-dontwarn org.jdom2.JDOMFactory
-dontwarn org.jdom2.Text
-dontwarn org.jdom2.input.SAXBuilder
-dontwarn org.joda.time.DateTime
-dontwarn org.joda.time.DateTimeZone
-dontwarn org.joda.time.format.DateTimeFormatter
-dontwarn org.joda.time.format.ISODateTimeFormat
-dontwarn org.kxml2.io.KXmlParser
-dontwarn org.xmlpull.mxp1.MXParser

#############################################
# 播放器核心保留（开启混淆后卡顿/黑屏）
#############################################
# 保留 ExoPlayer / Media3 全部公开 API 及内部实现类
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 保留 ExoPlayer 渲染器、轨道选择器等通过反射/SPI 加载的类
-keep class * extends androidx.media3.exoplayer.Renderer { *; }
-keep class * extends androidx.media3.exoplayer.trackselection.TrackSelector { *; }
-keep class androidx.media3.exoplayer.mediacodec.MediaCodecRenderer { *; }
-keepclassmembers class androidx.media3.exoplayer.mediacodec.MediaCodecRenderer { *; }

# 保留 IjkPlayer 所有类及 native 方法
-keep class tv.danmaku.ijk.** { *; }
-keepclasseswithmembernames class tv.danmaku.ijk.** {
    native <methods>;
}
-dontwarn tv.danmaku.ijk.**

# 显式保留被 native 调用的 IjkPlayer 方法/字段
-keep @tv.danmaku.ijk.media.player.annotations.CalledByNative class * {
    <methods>;
    <fields>;
}
-keep @tv.danmaku.ijk.media.player.annotations.AccessedByNative class * {
    <methods>;
    <fields>;
}
-keepclassmembers class * {
    @tv.danmaku.ijk.media.player.annotations.CalledByNative <methods>;
    @tv.danmaku.ijk.media.player.annotations.CalledByNative <fields>;
    @tv.danmaku.ijk.media.player.annotations.AccessedByNative <methods>;
    @tv.danmaku.ijk.media.player.annotations.AccessedByNative <fields>;
}

# 保留 dkplayer 全部核心模块（player / render / controller / util / exo / ijk）
-keep class xyz.doikki.videoplayer.** { *; }
-dontwarn xyz.doikki.videoplayer.**

# 保留 ExoPlayer 自定义 OkHttpDataSource 扩展包
-keep class com.google.androidx.media3.exoplayer.ext.okhttp.** { *; }
-keepclassmembers class com.google.androidx.media3.exoplayer.ext.okhttp.OkHttpDataSource$Factory {
    private java.lang.String userAgent;
}
-dontwarn com.google.androidx.media3.exoplayer.ext.okhttp.**

# 保留 Guava 相关类（OkHttpDataSource 使用了 Predicate / HttpHeaders）
-keep class com.google.common.base.Predicate { *; }
-keep class com.google.common.net.HttpHeaders { *; }
-dontwarn com.google.common.**

# 保留播放器包装类、自定义 View / Render、控制器、第三方播放器、弹幕
-keep class com.github.tvbox.osc.player.** { *; }

# 保留 IJKCode 完整类（JSON 反序列化用）
-keep class com.github.tvbox.osc.bean.IJKCode { *; }

# 保留字幕相关类
-keep class com.github.tvbox.osc.subtitle.** { *; }
-dontwarn com.github.tvbox.osc.subtitle.**

# 保留反射/序列化相关属性
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
