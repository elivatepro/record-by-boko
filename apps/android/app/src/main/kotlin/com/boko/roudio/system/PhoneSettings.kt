package com.boko.roudio.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** The Android permissions and background settings the setup flow (11c, 11d) asks for. */
object PhoneSettings {
    /** Nearby devices: finding and talking to the recorder. Location stands in before Android 12. */
    val nearbyDevices: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Empty before Android 13, where notifications need no permission. */
    val notifications: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    /** Wi-Fi fast transfer. Optional; empty before Android 13. */
    val nearbyWifi: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            emptyArray()
        }

    fun granted(context: Context, permissions: Array<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Roudio's page in Android settings, where refused permissions can be switched on. */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    // Battery

    /** "No restrictions" on Xiaomi; Android's battery optimisation exemption elsewhere. */
    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Android's own "let this app always run in the background?" dialog. */
    @SuppressLint("BatteryLife") // Roudio's whole job is all-day background transfers (decision 9).
    fun requestBatteryUnrestricted(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    // Xiaomi Autostart

    val isXiaomi: Boolean =
        Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    enum class Autostart { On, Off, Unknown }

    /**
     * Xiaomi keeps Autostart in a private app-op (10008). Android offers no public way to read it,
     * so this uses the hidden int-op check; when Android blocks that, the answer is [Unknown].
     */
    fun autostart(context: Context): Autostart {
        if (!isXiaomi) return Autostart.Unknown
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val check = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            when (check.invoke(ops, XIAOMI_AUTOSTART_OP, Process.myUid(), context.packageName) as Int) {
                AppOpsManager.MODE_ALLOWED -> Autostart.On
                else -> Autostart.Off
            }
        } catch (e: Exception) {
            Autostart.Unknown
        }
    }

    /** Xiaomi's Autostart list, or Roudio's settings page when that screen isn't there. */
    fun autostartSettings(context: Context): Intent {
        val xiaomi = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        return if (xiaomi.resolveActivity(context.packageManager) != null) xiaomi else appDetails(context)
    }

    private const val XIAOMI_AUTOSTART_OP = 10008
}
