package com.boko.roudio.recorder

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.tinnotech.penblesdk.entity.BleDevice
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import sdk.NiceBuildSdk
import sdk.PlaudDeviceAgent
import sdk.PlaudDeviceAgentListener

/**
 * [RecorderSource] on the Plaud SDK (app/libs/README.md). Follows Plaud's template app
 * (`DeviceManager.kt`) for the parts the SDK leaves to the app: the partner API base URL,
 * signing the serial before connecting, and reporting the binding to Plaud's cloud.
 *
 * A connection only counts once the handshake finishes (`bleBind` status 0). A recorder still
 * locked to another account accepts the Bluetooth link and then refuses the handshake.
 */
class PlaudRecorderSource(
    context: Context,
    private val tokens: PlaudTokens,
    private val scope: CoroutineScope,
) : RecorderSource {
    private val context = context.applicationContext
    private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RecorderEvent> = _events

    private val http = OkHttpClient()
    private val setupLock = Mutex()
    private var tokenInUse: PlaudToken? = null

    /** Latest scan results by serial; connecting needs the SDK's own device object. */
    private val scanned = ConcurrentHashMap<String, BleDevice>()

    /** Serial we're handshaking with, until `bleBind` or a drop. */
    @Volatile private var handshakeSerial: String? = null

    /** True when the handshake is a first link (from the setup flow), not a reconnect. */
    @Volatile private var firstLink = false

    /** The last connect stage the SDK reported; tells a refusal apart from a drop. */
    @Volatile private var lastStage: String? = null

    /** Serial to connect to as soon as a scan finds it (reconnecting). */
    @Volatile private var reconnectSerial: String? = null

    @Volatile private var connectedSerial: String? = null

    /** Use the force-clear handshake for first links (see [handshake]). Debug builds can switch it. */
    @Volatile var forceClearOnFirstLink = true
    @Volatile private var depairDone: CompletableDeferred<Unit>? = null

    init {
        // The SDK's log files. Builds before the silent Timber (src/main/java/timber) wrote
        // tokens and keys into them; now they stay empty.
        File(this.context.filesDir, SDK_LOG_DIR).deleteRecursively()
        PlaudDeviceAgent.listener = Listener()
        ContextCompat.registerReceiver(
            this.context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> emit(RecorderEvent.BluetoothChanged(true))
                        BluetoothAdapter.STATE_OFF -> {
                            handshakeSerial = null
                            connectedSerial = null
                            emit(RecorderEvent.BluetoothChanged(false))
                        }
                    }
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun isBluetoothOn(): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    override fun startSearch() {
        scanned.clear()
        scope.launch {
            // Scanning works without Plaud's servers; only linking needs them.
            runCatching { setUp() }.onFailure { Log.w(TAG, "SDK set-up before search failed", it) }
            sdk("startScan") { PlaudDeviceAgent.startScan() }
        }
    }

    override fun stopSearch() {
        reconnectSerial = null
        scope.launch { sdk("stopScan") { PlaudDeviceAgent.stopScan() } }
    }

    override fun link(serial: String) {
        reconnectSerial = null
        firstLink = true
        scope.launch { handshake(serial) }
    }

    override fun connect(serial: String) {
        if (connectedSerial == serial || handshakeSerial != null) return
        firstLink = false
        scope.launch {
            runCatching { setUp() }.onFailure {
                Log.w(TAG, "SDK set-up before reconnect failed", it)
                return@launch
            }
            val device = scanned[serial]
            if (device != null) {
                handshake(serial)
            } else {
                // The recorder may have a new address since; find it by serial first.
                reconnectSerial = serial
                sdk("startScan") { PlaudDeviceAgent.startScan() }
                delay(RECONNECT_SCAN_MS)
                if (reconnectSerial == serial) {
                    // Not in range. KeepConnected tries again later.
                    reconnectSerial = null
                    sdk("stopScan") { PlaudDeviceAgent.stopScan() }
                }
            }
        }
    }

    override fun disconnect() {
        reconnectSerial = null
        handshakeSerial = null
        scope.launch { sdk("disconnect") { PlaudDeviceAgent.disconnect() } }
    }

    override suspend fun unlink(serial: String) {
        reconnectSerial = null
        // Plaud's order: cloud first, then the recorder. Neither failure blocks unlinking.
        runCatching { cloudBinding("unbind", serial) }.onFailure { Log.w(TAG, "cloud unbind failed", it) }
        if (connectedSerial == serial) {
            val done = CompletableDeferred<Unit>().also { depairDone = it }
            sdk("depair") { PlaudDeviceAgent.depair(false) }
            withTimeoutOrNull(DEPAIR_WAIT_MS) { done.await() }
            depairDone = null
        }
        handshakeSerial = null
        connectedSerial = null
        sdk("disconnect") { PlaudDeviceAgent.disconnect() }
        scanned.clear()
    }

    override fun refresh() {
        scope.launch {
            sdk("getChargingState") { PlaudDeviceAgent.getChargingState() }
            sdk("getStorage") { PlaudDeviceAgent.getStorage() }
            sdk("refreshRecordState") { PlaudDeviceAgent.refreshRecordState() }
        }
    }

    /** Initialise the SDK once, and hand it a renewed token when ours changes. */
    private suspend fun setUp() = setupLock.withLock {
        val token = tokens.current()
        val inUse = tokenInUse
        when {
            inUse == null -> withContext(Dispatchers.Main) {
                // Known SDK bug: the partner API ignores initSDK's domain, so set it first.
                NiceBuildSdk.getPartnerApiManager().updateBaseUrl("https://${token.domain}")
                PlaudDeviceAgent.initSDK(context, token.accessToken, token.domain)
                PlaudDeviceAgent.setBleLogLevel(Log.INFO)
            }
            inUse.accessToken != token.accessToken ->
                withContext(Dispatchers.Main) { NiceBuildSdk.setPartnerToken(token.accessToken) }
        }
        tokenInUse = token
    }

    private suspend fun handshake(serial: String) {
        try {
            setUp()
        } catch (e: Exception) {
            Log.w(TAG, "SDK set-up failed", e)
            return linkFailed(serial, if (e is IOException) LinkFailure.NoInternet else LinkFailure.Other)
        }
        val device = scanned[serial] ?: return linkFailed(serial, LinkFailure.Dropped)

        // initSDK fetches the partner keys over the network; the handshake needs them.
        val ready = withTimeoutOrNull(PARTNER_KEYS_WAIT_MS) {
            while (!NiceBuildSdk.isPartnerDataReady()) delay(200)
            true
        } ?: false
        if (!ready) return linkFailed(serial, LinkFailure.NoInternet)

        val signed = runCatching { NiceBuildSdk.signAndStoreDeviceSn(deviceType(serial), serial) }
            .getOrDefault(false)
        if (!signed) Log.w(TAG, "signing the serial failed; the handshake may be refused")

        lastStage = null
        handshakeSerial = serial
        val userId = tokenInUse?.let { handshakeUserId(it.accessToken) }
        if (firstLink && userId != null && forceClearOnFirstLink) {
            // A recorder keeps the public key of the last app it was linked to and encrypts its
            // first reply with it, so a recorder freed from the Plaud app answers with a key we
            // can't read (the SDK fails with an RSA error, then times out). The force-clear
            // handshake (0xFE20) tells it to drop that key and take ours. The SDK only exposes
            // it as "recovery"; given our own user id it's an ordinary link as us.
            sdk("recoveryConnectBleDevice") { PlaudDeviceAgent.recoveryConnectBleDevice(device, userId) }
        } else {
            sdk("connectBleDevice") { PlaudDeviceAgent.connectBleDevice(device) }
        }
    }

    private fun onHandshakeDone(serial: String) {
        handshakeSerial = null
        reconnectSerial = null
        val wasFirstLink = firstLink
        firstLink = false
        scope.launch {
            // Report the binding every time (Plaud: idempotent for the same owner).
            val status = runCatching { cloudBinding("bind", serial) }
                .onFailure { Log.w(TAG, "cloud bind failed; retried on the next connect", it) }
                .getOrNull()
            if (status == HTTP_FORBIDDEN) {
                // Plaud says another account owns it.
                sdk("disconnect") { PlaudDeviceAgent.disconnect() }
                if (wasFirstLink) emit(RecorderEvent.LinkFailed(serial, LinkFailure.LinkedElsewhere))
                return@launch
            }
            connectedSerial = serial
            emit(RecorderEvent.Connected(serial))
            // Recordings are stamped with the recorder's clock; keep it on the phone's time.
            sdk("syncTime") { PlaudDeviceAgent.syncTime() }
        }
    }

    private fun onConnectionLost(state: Int) {
        val serial = handshakeSerial
        if (serial != null) {
            handshakeSerial = null
            // Reaching the handshake and then being dropped is how a locked recorder says no.
            val refused = lastStage?.contains("handshake", ignoreCase = true) == true
            Log.i(TAG, "lost during handshake (state=$state, stage=$lastStage, refused=$refused)")
            if (firstLink) linkFailed(serial, if (refused) LinkFailure.LinkedElsewhere else LinkFailure.Dropped)
            return
        }
        if (connectedSerial != null) {
            connectedSerial = null
            emit(RecorderEvent.Disconnected)
        }
    }

    private fun linkFailed(serial: String, reason: LinkFailure) {
        firstLink = false
        emit(RecorderEvent.LinkFailed(serial, reason))
    }

    /** `POST /open/partner/sdk/{bind|unbind}` with the user token. Returns the HTTP status. */
    private suspend fun cloudBinding(action: String, serial: String): Int = withContext(Dispatchers.IO) {
        val token = tokens.current()
        val body = JSONObject().put("type", deviceType(serial)).put("sn", serial).toString()
        val request = Request.Builder()
            .url("https://${token.domain}/developer/api/open/partner/sdk/$action")
            .header("Authorization", "Bearer ${token.accessToken}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            Log.i(TAG, "cloud $action: HTTP ${response.code}")
            response.code
        }
    }

    private fun emit(event: RecorderEvent) {
        if (!_events.tryEmit(event)) Log.w(TAG, "event dropped: $event")
    }

    /** SDK calls run on the main thread, as in Plaud's template, and never throw out of here. */
    private suspend fun sdk(name: String, call: () -> Unit) {
        withContext(Dispatchers.Main) {
            try {
                call()
            } catch (e: Exception) {
                // Usually a missing Nearby devices permission.
                Log.w(TAG, "$name failed", e)
            }
        }
    }

    private inner class Listener : PlaudDeviceAgentListener {
        override fun bleScanResult(devices: List<BleDevice>) {
            val found = devices.mapNotNull { device ->
                val serial = device.serialNumber?.takeIf(::isSupported) ?: return@mapNotNull null
                if (scanned.put(serial, device) == null) {
                    Log.i(
                        TAG,
                        "found ${serial.serialEnding}: firmware ${device.versionType}${device.versionCode} " +
                            "(${device.versionName}) bindInfo=${device.bindInfo} ok=${device.isBindInfoOk} " +
                            "protocol=${device.portVersion}",
                    )
                }
                FoundRecorder(serial, RecorderModel.fromSerial(serial), device.rssi)
            }
            emit(RecorderEvent.Found(found))
            val wanted = reconnectSerial
            if (wanted != null && scanned.containsKey(wanted)) {
                reconnectSerial = null
                scope.launch {
                    sdk("stopScan") { PlaudDeviceAgent.stopScan() }
                    handshake(wanted)
                }
            }
        }

        override fun bleConnectState(state: Int) {
            Log.i(TAG, "connect state $state")
            // 1 = Bluetooth link up (not linked yet), 0 = disconnected, 2 = connect failed.
            if (state == 0 || state == 2) onConnectionLost(state)
        }

        override fun bleConnectStage(sn: String?, stage: String, detail: String?) {
            Log.i(TAG, "connect stage $stage ${detail.orEmpty()}")
            lastStage = stage
        }

        override fun bleBind(sn: String?, status: Int, protVersion: Int, timezone: Int) {
            Log.i(TAG, "bind status $status")
            val serial = sn ?: handshakeSerial ?: return
            if (status == 0) {
                onHandshakeDone(serial)
            } else if (handshakeSerial != null) {
                handshakeSerial = null
                if (firstLink) linkFailed(serial, LinkFailure.LinkedElsewhere)
            }
        }

        override fun bleDepair(status: Int) {
            Log.i(TAG, "depair status $status")
            depairDone?.complete(Unit)
        }

        override fun blePowerChange(power: Int, oldPower: Int) = emit(RecorderEvent.Battery(power))

        override fun bleChargingState(isCharging: Boolean, level: Int) =
            emit(RecorderEvent.Battery(level, isCharging))

        override fun bleStorage(total: Long, free: Long, duration: Long) {
            Log.i(TAG, "storage total=$total free=$free duration=$duration")
            emit(RecorderEvent.Storage(total, free))
        }

        override fun bleRecordStart(sessionId: Long, start: Long, status: Int, scene: Int, startTime: Long, reason: Int) {
            Log.i(TAG, "record start session=$sessionId start=$start startTime=$startTime")
            emit(RecorderEvent.Recording(true, sessionStart(sessionId)))
        }

        override fun bleRecordResume(sessionId: Long, start: Long, status: Int, scene: Int, startTime: Long) =
            emit(RecorderEvent.Recording(true, sessionStart(sessionId)))

        override fun bleRecordPause(sessionId: Long, reason: Int, fileExist: Boolean, fileSize: Long) =
            emit(RecorderEvent.Recording(false))

        override fun bleRecordStop(sessionId: Long, reason: Int, fileExist: Boolean, fileSize: Long) =
            emit(RecorderEvent.Recording(false))

        override fun bleRecordStateRefreshed(isRecording: Boolean, sessionId: Long) {
            Log.i(TAG, "record state recording=$isRecording session=$sessionId")
            emit(RecorderEvent.Recording(isRecording, if (isRecording) sessionStart(sessionId) else null))
        }
    }

    private companion object {
        const val TAG = "Recorder"
        const val PARTNER_KEYS_WAIT_MS = 10_000L
        const val DEPAIR_WAIT_MS = 3_000L
        const val RECONNECT_SCAN_MS = 15_000L
        const val HTTP_FORBIDDEN = 403
        const val SDK_LOG_DIR = "plaud_sdk_logs"

        /** Note Pro (881) and NotePin S (882), as in Plaud's template. */
        fun isSupported(serial: String) = serial.startsWith("881") || serial.startsWith("882")

        /** Plaud's cloud registry type for a serial (template `getDeviceType`). */
        fun deviceType(serial: String) = when {
            serial.startsWith("881") -> "notepro"
            serial.startsWith("880") -> "notepin"
            serial.startsWith("882") -> "notepins"
            else -> "note"
        }

        /**
         * The user id the SDK handshakes with: the `sub` claim of the Plaud user token
         * (as `NiceBuildSdk.resolveHandshakeToken` reads it). Not a secret on its own.
         */
        fun handshakeUserId(token: String): String? = runCatching {
            val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            JSONObject(json).optString("sub").takeIf { it.isNotBlank() }
        }.getOrNull()

        /** Plaud session ids are the recording's start in Unix seconds. Anything else is unknown. */
        fun sessionStart(sessionId: Long): Instant? =
            sessionId.takeIf { it in 1_577_836_800L..4_102_444_800L }?.let(Instant::ofEpochSecond)
    }
}
