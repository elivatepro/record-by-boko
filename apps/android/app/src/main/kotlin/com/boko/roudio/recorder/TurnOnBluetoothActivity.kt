package com.boko.roudio.recorder

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.boko.roudio.system.PhoneSettings

/**
 * An invisible screen that only shows Android's "turn on Bluetooth?" dialog, then closes, so it
 * can be raised from the background (RecorderService) or the notification without opening Roudio.
 * Android 13+ doesn't let an app switch Bluetooth on by itself.
 */
class TurnOnBluetoothActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // The dialog is already up.
        val on = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        if (on || !PhoneSettings.granted(this, PhoneSettings.nearbyDevices)) {
            finish()
        } else {
            ask.launch(PhoneSettings.enableBluetooth(this))
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, TurnOnBluetoothActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
