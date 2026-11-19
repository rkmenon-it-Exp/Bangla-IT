package com.example.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.example.BuildConfig

object DeviceTelemetryHelper {

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context, fallbackUuid: String = ""): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().trim()

        return if (androidId.isNotBlank() && !androidId.equals("9774d56d682e549c", ignoreCase = true)) {
            androidId
        } else {
            fallbackUuid.trim()
        }
    }

    fun getDeviceModel(): String = Build.MODEL.orEmpty()

    fun getOsVersion(): String = "Android ${Build.VERSION.RELEASE.orEmpty()}"

    fun getAppVersion(): String = BuildConfig.VERSION_NAME

    fun getBatteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val prop = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
        if (prop in 0..100) return prop

        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else -1
    }

    fun buildRequestHeaders(context: Context, deviceId: String): Map<String, String> {
        val resolvedId = deviceId.ifBlank { getDeviceId(context) }
        val battery = getBatteryLevel(context)
        return buildMap {
            if (resolvedId.isNotBlank()) put("X-Device-Id", resolvedId)
            put("X-Device-Model", getDeviceModel())
            put("X-OS-Version", getOsVersion())
            put("X-App-Version", getAppVersion())
            if (battery in 0..100) put("X-Battery-Level", battery.toString())
        }
    }
}
