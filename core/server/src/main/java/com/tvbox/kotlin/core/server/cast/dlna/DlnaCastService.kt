package com.tvbox.kotlin.core.server.cast.dlna

import org.fourthline.cling.UpnpServiceConfiguration
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.model.types.ServiceType
import org.fourthline.cling.model.types.UDAServiceType

/**
 * DLNA 控制点服务。
 *
 * 仅保留 AVTransport 服务类型，减少无关设备与服务的发现开销。
 */
class DlnaCastService : AndroidUpnpServiceImpl() {

    override fun createConfiguration(): UpnpServiceConfiguration =
        object : DlnaServiceConfiguration() {
            override fun getExclusiveServiceTypes(): Array<ServiceType> =
                arrayOf(UDAServiceType("AVTransport", 1))
        }
}
