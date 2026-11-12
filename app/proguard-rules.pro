#############################################
# 基础优化配置
#############################################
-verbose
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,LineNumberTable,SourceFile,Exceptions
-renamesourcefileattribute SourceFile

#############################################
# Android 基础保留
#############################################
# 保留四大组件及Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
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

#############################################
# Kotlin & 注解支持
#############################################
# androidx.annotation.Keep 注解（项目中有大量 @Keep）
-keep @androidx.annotation.Keep class * { *; }

#############################################
# DataBinding
#############################################
-keep class androidx.databinding.** { *; }
-dontwarn androidx.databinding.**
-keep class * extends androidx.databinding.DataBinderMapper { *; }
-keep class * extends androidx.databinding.ViewDataBinding { *; }
-keep class com.tvbox.kotlin.debug.BR { *; }

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
# Gson 序列化（jar 爬虫字节码按原始类名引用，必须整包保留，不可混淆）
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
# OkHttp / Okio（jar 爬虫字节码按原始类名引用，必须整包保留，不可混淆）
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *; }

# LoadSir
-dontwarn com.kingja.loadsir.**
-keep class com.kingja.loadsir.** { *; }

# Sardine WebDAV
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-dontwarn com.thegrizzlylabs.sardineandroid.**

# NanoHttpd
-keep class fi.iki.elonen.** { *; }

# xmlpull
-keep class org.xmlpull.v1.** { *; }

# Conscrypt
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.psse.SSLParametersImpl
-dontwarn org.conscrypt.**

# XXPermissions（28.3 版本 AAR 已自带 consumer ProGuard 规则，此处再做一层兜底保留）
-keep class com.hjq.permissions.** { *; }
-keep interface com.hjq.permissions.** { *; }
-keepclassmembers class com.hjq.permissions.** { *; }
-dontwarn com.hjq.permissions.**

# zxing（jar 爬虫验证码识别按原始类名引用，必须保留）
-keep class com.google.zxing.** { *; }

# jsoup（jar 爬虫 HTML 解析按原始类名引用，必须保留）
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

#############################################
# TVBox 业务核心（必须保留）
#############################################
# Bean 包：涉及 Gson/XStream/Serializable 序列化，必须保留类名、字段名和方法名
-keep class com.tvbox.kotlin.core.common.bean.** { *; }
-keep class com.tvbox.kotlin.core.data.entity.** { *; }

# 爬虫相关：动态加载的类不能被混淆
-keep interface com.github.catvod.crawler.Spider { *; }
-keep class * implements com.github.catvod.crawler.Spider {
    <init>();
    public <methods>;
}
-keep class com.github.catvod.** { *; }
-keep class com.tvbox.kotlin.core.spider.** { *; }

# JS 桥接：Global / local 类通过反射向 QuickJS 暴露方法
-keep class com.tvbox.kotlin.app.js.** { *; }

# 其他核心业务包（Server/UI/ViewModel，播放器相关在下方单独保留）
-keep class com.tvbox.kotlin.core.server.** { *; }
-keep class com.tvbox.kotlin.feature.**.ui.** { *; }

# Python 支持
-keep public class com.tvbox.kotlin.engine.pyramid.** { *; }
-keep public class com.undcover.freedom.pyramid.** { *; }
-dontwarn com.tvbox.kotlin.engine.pyramid.**
-dontwarn com.undcover.freedom.pyramid.**

# Chaquopy：native 层（chaquopy.so）按名访问 Java 类/字段/方法，类与成员均需完整保留
-keep class com.chaquo.python.** { *; }
-keep interface com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# 开源 BT 引擎 jlibtorrent（frostwire-jlibtorrent，SWIG JNI 绑定，需保留完整类结构）
-keep class com.frostwire.jlibtorrent.** { *; }
-dontwarn com.frostwire.jlibtorrent.**

#############################################
# 保留重要工具类（避免反射或序列化失败）
#############################################
-keep class com.tvbox.kotlin.core.common.util.DefaultConfig { *; }
-keep class com.tvbox.kotlin.core.common.util.AppConstants { *; }
-keep class com.tvbox.kotlin.core.common.LOG { *; }
-keep class com.tvbox.kotlin.core.common.util.MD5 { *; }

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
# （DefaultRenderersFactory 通过 Class.forName 反射加载 FfmpegAudioRenderer 扩展，类名必须保留）
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 保留 ExoPlayer 渲染器、轨道选择器等通过反射/SPI 加载的类
-keep class * extends androidx.media3.exoplayer.Renderer { *; }
-keep class * extends androidx.media3.exoplayer.trackselection.TrackSelector { *; }
-keep class androidx.media3.exoplayer.mediacodec.MediaCodecRenderer { *; }
-keepclassmembers class androidx.media3.exoplayer.mediacodec.MediaCodecRenderer { *; }

# 保留 GSYVideoPlayer 全部类（内部通过反射创建 Exo2 PlayerManager 内核）
-keep class com.shuyu.gsyvideoplayer.** { *; }
-keep interface com.shuyu.gsyvideoplayer.** { *; }
-dontwarn com.shuyu.gsyvideoplayer.**

# 保留 GSY 边播边缓存（AndroidVideoCache，包名 com.danikula.videocache）
-keep class com.danikula.videocache.** { *; }
-dontwarn com.danikula.videocache.**

# 保留 IJK Exo2 内核（IjkExo2MediaPlayer 内部字段 mInternalPlayer 通过反射访问，
# 且实现 IMediaPlayer 接口回调，混淆会导致缓冲状态/首帧监听失效）
-keep class tv.danmaku.ijk.** { *; }
-keep interface tv.danmaku.ijk.** { *; }
-dontwarn tv.danmaku.ijk.**

# 保留 DanmakuFlameMaster 弹幕库（含 native 加载类 com.ctiao）
-keep class master.flame.danmaku.** { *; }
-keep class com.ctiao.** { *; }
-dontwarn master.flame.danmaku.**
-dontwarn com.ctiao.**

# 保留播放器包装类、自定义 View / Render、控制器、第三方播放器、弹幕
-keep class com.tvbox.kotlin.core.player.** { *; }

#############################################
# XML 反射 inflate / JNI 符号 / 反射注册
#############################################
# 荐片 P2P JNI：包名/类名必须与 libp2p.so 的 JNI 符号完全匹配
-keep class com.p2p.P2PClass { *; }

# TVRecyclerView（布局 XML 反射 inflate 实例化，类名/构造不可混淆）
-keep class com.owen.tvrecyclerview.** { *; }
-keep interface com.owen.tvrecyclerview.** { *; }
-dontwarn com.owen.tvrecyclerview.**

# FlowLayout
-keep class com.yang.flowlayoutlibrary.** { *; }

# AutoSize
-keep class me.jessyan.autosize.** { *; }
-keep interface me.jessyan.autosize.** { *; }
-dontwarn me.jessyan.autosize.**

# core:ui 业务控件
-keep class com.tvbox.kotlin.core.ui.widget.** { *; }
-keep class com.tvbox.kotlin.core.ui.callback.** { *; }

# QuickJS 引擎
-keep class com.tvbox.kotlin.engine.quickjs.** { *; }

# 下载协议封装（开源 BT 引擎 / 本地 HTTP 流 / 荐片 P2P）
-keep class com.tvbox.kotlin.app.thunder.** { *; }

# Conscrypt TLS Provider 注册
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
