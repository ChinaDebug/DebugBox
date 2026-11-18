package com.tvbox.kotlin.core.server.cast.dlna

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.tvbox.kotlin.core.common.LOG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fourthline.cling.android.AndroidUpnpService
import org.fourthline.cling.controlpoint.ControlPoint
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.message.header.STAllHeader
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.meta.RemoteService
import org.fourthline.cling.model.types.UDADeviceType
import org.fourthline.cling.model.types.UDAServiceType
import org.fourthline.cling.registry.DefaultRegistryListener
import org.fourthline.cling.registry.Registry
import org.fourthline.cling.support.avtransport.callback.Play
import org.fourthline.cling.support.avtransport.callback.Seek
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI
import org.fourthline.cling.support.model.SeekMode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * DLNA 投屏管理器。
 *
 * 通过 [DlnaCastService] 绑定 cling 控制点，发现 MediaRenderer 后以 AVTransport
 * 推送直链并跳转到指定进度。绑定/解绑必须成对调用，避免服务连接泄漏。
 */
class DlnaCastManager : DefaultRegistryListener(), ServiceConnection {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceMap = ConcurrentHashMap<String, DlnaRenderer>()

    private val _devices = MutableStateFlow<List<DlnaRenderer>>(emptyList())
    val devices: StateFlow<List<DlnaRenderer>> = _devices.asStateFlow()

    private var upnpService: AndroidUpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var binding = false

    /** 绑定控制点并开始搜索；已绑定时只刷新搜索 */
    fun init(context: Context) {
        if (upnpService != null || binding) {
            search()
            return
        }
        val appContext = context.applicationContext
        acquireMulticastLock(appContext)
        binding = appContext.bindService(
            Intent(context, DlnaCastService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
        if (!binding) releaseMulticastLock()
    }

    /** 解绑服务并清空设备列表 */
    fun release(context: Context) {
        try {
            upnpService?.registry?.removeListener(this)
            context.applicationContext.unbindService(this)
        } catch (_: Exception) {
            // ignore: 解绑失败时仍继续清理本地状态
        }
        upnpService = null
        binding = false
        deviceMap.clear()
        publishDevices()
        releaseMulticastLock()
    }

    fun search() {
        val service = upnpService ?: return
        service.controlPoint.search(STAllHeader())
        for (device in service.registry.getDevices(RENDERER_TYPE)) {
            if (device is RemoteDevice) addDevice(device)
        }
    }

    fun cast(
        renderer: DlnaRenderer,
        url: String,
        title: String,
        positionMs: Long,
        callback: ((Boolean, String?) -> Unit)?
    ) {
        val control = upnpService?.controlPoint
        val service = findAvTransport(renderer.id)
        if (control == null || service == null) {
            postResult(callback, false, "设备不在线")
            return
        }
        LOG.i(TAG, "dlna-cast start device=${renderer.name}, url=$url")
        control.execute(uriAction(control, service, url, title, positionMs, callback))
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            if (multicastLock?.isHeld == true) return
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wifiManager.createMulticastLock("tvbox_dlna_cast")
            multicastLock = lock
            lock.setReferenceCounted(false)
            lock.acquire()
        } catch (_: Exception) {
            // ignore: 组播锁不可用时仅影响设备发现，不阻断投屏
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {
            // ignore: 释放失败不影响后续清理
        }
        multicastLock = null
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binding = false
        val upnp = service as AndroidUpnpService
        upnpService = upnp
        upnp.registry.addListener(this)
        search()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        upnpService = null
        binding = false
    }

    override fun remoteDeviceAdded(registry: Registry?, device: RemoteDevice?) {
        val d = device ?: return
        if (d.type.implementsVersion(RENDERER_TYPE)) addDevice(d)
    }

    override fun remoteDeviceRemoved(registry: Registry?, device: RemoteDevice?) {
        val d = device ?: return
        if (d.type.implementsVersion(RENDERER_TYPE)) removeDevice(d)
    }

    private fun addDevice(device: RemoteDevice) {
        val id = device.identity.udn.identifierString
        val friendlyName = device.details?.friendlyName
        val name = friendlyName?.takeIf { it.isNotBlank() } ?: "DLNA"
        deviceMap[id] = DlnaRenderer(id, name)
        publishDevices()
    }

    private fun removeDevice(device: RemoteDevice) {
        val id = device.identity.udn.identifierString
        deviceMap.remove(id)
        publishDevices()
    }

    private fun publishDevices() {
        _devices.value = deviceMap.values.sortedBy { it.name }
    }

    private fun findAvTransport(rendererId: String): RemoteService? {
        val service = upnpService ?: return null
        for (item in service.registry.getDevices(RENDERER_TYPE)) {
            if (item !is RemoteDevice) continue
            if (item.identity.udn.identifierString == rendererId) {
                return item.findService(AVT_TYPE)
            }
        }
        return null
    }

    private fun uriAction(
        control: ControlPoint,
        service: RemoteService,
        url: String,
        title: String,
        positionMs: Long,
        callback: ((Boolean, String?) -> Unit)?
    ): SetAVTransportURI = object : SetAVTransportURI(service, url, buildMetaData(url, title)) {
        override fun success(invocation: ActionInvocation<*>?) {
            control.execute(playAction(control, service, positionMs, callback))
        }

        override fun failure(
            invocation: ActionInvocation<*>?,
            operation: UpnpResponse?,
            defaultMsg: String?
        ) {
            LOG.e(TAG, "dlna-cast SetAVTransportURI failure: ${formatResponse(operation, defaultMsg)}")
            postResult(callback, false, defaultMsg)
        }
    }

    private fun playAction(
        control: ControlPoint,
        service: RemoteService,
        positionMs: Long,
        callback: ((Boolean, String?) -> Unit)?
    ): Play = object : Play(service) {
        override fun success(invocation: ActionInvocation<*>?) {
            if (positionMs > 0) control.execute(seekAction(service, positionMs))
            postResult(callback, true, null)
        }

        override fun failure(
            invocation: ActionInvocation<*>?,
            operation: UpnpResponse?,
            defaultMsg: String?
        ) {
            LOG.e(TAG, "dlna-cast Play failure: ${formatResponse(operation, defaultMsg)}")
            postResult(callback, false, defaultMsg)
        }
    }

    private fun seekAction(service: RemoteService, positionMs: Long): Seek =
        object : Seek(service, SeekMode.REL_TIME, formatMs(positionMs)) {
            override fun success(invocation: ActionInvocation<*>?) = Unit

            override fun failure(
                invocation: ActionInvocation<*>?,
                operation: UpnpResponse?,
                defaultMsg: String?
            ) {
                LOG.i(TAG, "dlna-cast Seek ignored: ${formatResponse(operation, defaultMsg)}")
            }
        }

    /** DIDL-Lite 元数据：部分电视缺少元数据会拒绝播放 */
    private fun buildMetaData(url: String, title: String): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"0\">")
        append("<dc:title>").append(escapeXml(title)).append("</dc:title>")
        append("<dc:creator></dc:creator>")
        append("<upnp:class>object.item.videoItem</upnp:class>")
        append("<res protocolInfo=\"http-get:*:video/*:*\">").append(escapeXml(url)).append("</res>")
        append("</item>")
        append("</DIDL-Lite>")
    }

    private fun escapeXml(value: String?): String {
        if (value == null) return ""
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&apos;")
            .replace("\"", "&quot;")
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun formatResponse(operation: UpnpResponse?, defaultMsg: String?): String {
        if (operation == null) return defaultMsg ?: ""
        return "${operation.statusCode} ${operation.statusMessage} ${defaultMsg ?: ""}"
    }

    private fun postResult(callback: ((Boolean, String?) -> Unit)?, success: Boolean, msg: String?) {
        mainHandler.post { callback?.invoke(success, msg) }
    }

    companion object {
        private const val TAG = "DlnaCastManager"

        private val RENDERER_TYPE = UDADeviceType("MediaRenderer", 1)
        private val AVT_TYPE = UDAServiceType("AVTransport", 1)
    }
}
