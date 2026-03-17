package com.example.shiori.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日付フォーマットユーティリティ。
 * [DateTimeFormatter] はイミュータブルでスレッドセーフ（minSdk 26以上で利用可）。
 */
private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())

fun formatDate(millis: Long): String =
    DATE_FORMATTER.format(Instant.ofEpochMilli(millis))

