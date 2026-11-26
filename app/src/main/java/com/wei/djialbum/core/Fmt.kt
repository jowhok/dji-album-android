package com.wei.djialbum.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return if (i == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun fmtDuration(seconds: Double?): String {
    val s = (seconds ?: 0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

/** dirDate 形如 20261124 */
fun fmtDate(dirDate: String): String =
    if (dirDate.length == 8)
        "${dirDate.substring(0, 4)}-${dirDate.substring(4, 6)}-${dirDate.substring(6, 8)}"
    else dirDate

fun fmtDateMonth(dirDate: String): String =
    if (dirDate.length == 8) "${dirDate.substring(0, 4)} 年 ${dirDate.substring(4, 6)} 月" else dirDate

fun fmtDateTime(epochMillis: Long): String =
    dateTimeFmt.format(Date(epochMillis))

fun fmtClock(epochMillis: Long): String = clockFmt.format(Date(epochMillis))

fun fmtEpochDate(epochMillis: Long): String = dateFmt.format(Date(epochMillis))

fun String.toDirDateKey(): String = replace("-", "")
