package com.tvbox.kotlin.core.common.bean

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 单条 EPG 节目信息 */
class Epginfo {

    var startdateTime: Date? = null
    var enddateTime: Date? = null
    var datestart: Int = 0
    var dateend: Int = 0
    var title: String? = null
    var originStart: String? = null
    var originEnd: String? = null
    var start: String? = null
    var end: String? = null
    var index: Int = 0
    var epgDate: Date? = null
    var currentEpgDate: String? = null

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    constructor(
        date: Date,
        str: String?,
        date1: Date,
        str1: String?,
        str2: String?,
        pos: Int
    ) {
        epgDate = date
        currentEpgDate = timeFormat.format(epgDate)
        title = str
        originStart = str1
        originEnd = str2
        index = pos
        val gmtFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        gmtFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
        val userFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        userFormat.timeZone = TimeZone.getDefault()
        val dateStr = gmtFormat.format(date1)
        startdateTime = userFormat.parse("$dateStr $str1:00 GMT+8:00", ParsePosition(0))
        enddateTime = userFormat.parse("$dateStr $str2:00 GMT+8:00", ParsePosition(0))
        if (enddateTime != null && startdateTime != null && enddateTime!!.before(startdateTime)) {
            val calendar = Calendar.getInstance()
            calendar.time = enddateTime
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            enddateTime = calendar.time
        }
        val zoneFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        start = zoneFormat.format(startdateTime)
        end = zoneFormat.format(enddateTime)
        datestart = start!!.replace(":", "").toIntOrNull() ?: 0
        dateend = end!!.replace(":", "").toIntOrNull() ?: 0
    }
}
