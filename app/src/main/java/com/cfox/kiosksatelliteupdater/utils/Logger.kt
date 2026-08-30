package com.cfox.kiosksatelliteupdater.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object Logger {
    private const val TAG = "KioskSatelliteUpdater"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logHistory = ConcurrentLinkedQueue<String>()
    private const val MAX_HISTORY = 100

    private val _logFlow = MutableSharedFlow<String>(extraBufferCapacity = 50)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    fun d(message: String) {
        Log.d(TAG, message)
        record("DEBUG", message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
        record("INFO", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        record("WARN", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val fullMsg = if (throwable != null) "$message | ${throwable.message}" else message
        record("ERROR", fullMsg)
    }

    private fun record(level: String, message: String) {
        val entry = "[${timeFormat.format(Date())}] [$level] $message"
        logHistory.add(entry)
        while (logHistory.size > MAX_HISTORY) {
            logHistory.poll()
        }
        _logFlow.tryEmit(entry)
    }

    fun getRecentLogs(): List<String> {
        return logHistory.toList()
    }
}
