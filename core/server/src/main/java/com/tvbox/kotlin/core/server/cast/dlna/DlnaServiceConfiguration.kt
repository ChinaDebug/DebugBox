package com.tvbox.kotlin.core.server.cast.dlna

import android.os.Build
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration
import org.fourthline.cling.binding.xml.ServiceDescriptorBinder
import org.fourthline.cling.binding.xml.UDA10ServiceDescriptorBinderImpl
import org.fourthline.cling.model.ServerClientTokens
import org.fourthline.cling.transport.spi.NetworkAddressFactory
import org.fourthline.cling.transport.spi.StreamClient
import org.fourthline.cling.transport.spi.StreamServer

/**
 * Android 版 UPnP 配置：替换 cling 默认的 Apache HttpClient 与 Jetty 服务端。
 */
open class DlnaServiceConfiguration : AndroidUpnpServiceConfiguration() {

    override fun createStreamClient(): StreamClient<*> =
        DlnaStreamClient(object : DlnaStreamClient.Configuration(getSyncProtocolExecutorService()) {
            override fun getUserAgentValue(majorVersion: Int, minorVersion: Int): String {
                val tokens = ServerClientTokens(majorVersion, minorVersion)
                tokens.setOsName("Android")
                tokens.setOsVersion(Build.VERSION.RELEASE)
                return tokens.toString()
            }
        })

    override fun createStreamServer(networkAddressFactory: NetworkAddressFactory): StreamServer<*> =
        DlnaStreamServer(DlnaStreamServer.Configuration(networkAddressFactory.streamListenPort))

    override fun createServiceDescriptorBinderUDA10(): ServiceDescriptorBinder =
        UDA10ServiceDescriptorBinderImpl()
}
