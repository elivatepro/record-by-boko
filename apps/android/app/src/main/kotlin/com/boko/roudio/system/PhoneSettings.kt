package com.boko.roudio.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.bluetooth.BluetoothAdapter
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

    /** Android's "turn on Bluetooth?" dialog (12b). Needs Nearby devices; else Bluetooth settings. */
    fun enableBluetooth(context: Context): Intent =
        if (granted(context, nearbyDevices)) {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        } else {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }

    /** The Plaud app (12d), or its Play Store page when it isn't installed. */
    fun plaudApp(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(PLAUD_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, "market://details?id=$PLAUD_PACKAGE".toUri())

    private const val PLAUD_PACKAGE = "ai.plaud.android.plaud"

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
        return when (xiaomiOp(context, XIAOMI_AUTOSTART_OP)) {
            true -> Autostart.On
            false -> Autostart.Off
            null -> Autostart.Unknown
        }
    }

    /** Xiaomi's Autostart list, or Roudio's settings page when that screen isn't there. */
    fun autostartSettings(context: Context): Intent {
        val xiaomi = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        return if (xiaomi.resolveActivity(context.packageManager) != null) xiaomi else appDetails(context)
    }

    // Pop-ups from the background

    /**
     * Whether Roudio may open "turn on Bluetooth?" while it isn't on screen: Android's "Display
     * over other apps", plus Xiaomi's "Display pop-up windows while running in the background"
     * (private app-op 10021). When the Xiaomi one can't be read, only Android's counts.
     */
    fun canPopUp(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (!isXiaomi) return true
        return xiaomiOp(context, XIAOMI_BACKGROUND_POPUP_OP) != false
    }

    /** Xiaomi's "Other permissions" page for Roudio, which holds both switches; else Android's. */
    fun popUpSettings(context: Context): Intent {
        val xiaomi = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName)
        if (isXiaomi && xiaomi.resolveActivity(context.packageManager) != null) return xiaomi
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
    }

    /** A private Xiaomi app-op through the hidden int-op check; null when Android blocks it. */
    private fun xiaomiOp(context: Context, op: Int): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val check = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        check.invoke(ops, op, Process.myUid(), context.packageName) as Int == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        null
    }

    private const val XIAOMI_AUTOSTART_OP = 10008
    private const val XIAOMI_BACKGROUND_POPUP_OP = 10021
}
