package com.example.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object LocationPermissionHelper {

    fun hasForegroundLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasForegroundLocationPermission(context)
        }
    }

    fun getBackgroundPermissionOptionLabel(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.packageManager.backgroundPermissionOptionLabel.toString()
            }.getOrDefault("Allow all the time")
        } else {
            "Allow all the time"
        }
    }

    fun startTrackingService(context: Context) {
        if (!hasForegroundLocationPermission(context)) return
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
        }
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun stopTrackingService(context: Context) {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        runCatching {
            context.stopService(intent)
        }
    }
}
