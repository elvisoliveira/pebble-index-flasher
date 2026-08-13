package poc.ringclick

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RingKind { OFFICIAL, FAILSAFE, CFW, NONE }

data class RingState(
    val kind: RingKind = RingKind.NONE,
    val name: String? = null,
    val address: String? = null,
    val rssi: Int = 0,
    /** Byte from the CFW click beacon (mfr 0xFFFF, payload[0]); null outside CFW. */
    val counter: Int? = null,
    val totalClicks: Int = 0,
    val lastSeenMs: Long = 0L,
)

/**
 * Continuous, connectionless BLE scan — the single source of truth about the ring
 * (PLAN §3.1). Match by name substring ("Pebble Index"); the state comes from the
 * suffix + the manufacturer-data company id:
 *   CFW      -> mfr 0xFFFF (payload[0] = click counter) / name "… CFW"
 *   FAILSAFE -> mfr 0x0EEA / name "… FBA"
 *   OFFICIAL -> any other "Pebble Index …" (advertises the stock service UUID)
 *
 * No ScanFilter: the native filter only matches names exactly and we need a
 * substring (the suffix varies between states).
 */
class RingScanner(private val context: Context, private val log: (String) -> Unit) {

    val state = MutableStateFlow(RingState())

    private var scanning = false
    private var lastCounter: Int? = null
    private var total = 0

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord ?: return
            // Advertised name, not the bonded one: device.name may be the pairing cache (§3.7 #5).
            @SuppressLint("MissingPermission")
            val name = rec.deviceName ?: return
            if (!name.contains(NAME_PART, ignoreCase = true)) return

            val cfwMfr = rec.getManufacturerSpecificData(CFW_COMPANY)
            val kind = when {
                cfwMfr != null || name.endsWith("CFW") -> RingKind.CFW
                rec.getManufacturerSpecificData(FAILSAFE_COMPANY) != null ||
                    name.endsWith("FBA") -> RingKind.FAILSAFE
                else -> RingKind.OFFICIAL
            }

            var counter: Int? = null
            if (kind == RingKind.CFW && cfwMfr != null && cfwMfr.isNotEmpty()) {
                counter = cfwMfr[0].toInt() and 0xFF
                val prev = lastCounter
                if (prev != null && counter != prev) {
                    val delta = (counter - prev + 256) % 256
                    // ponytail: a counter that DROPS is ambiguous (wrap 255->0 vs reboot->0).
                    // Heuristic: a drop with a large modular delta = reboot (the counter
                    // resets on power-up, §3.2); real clicks between two packets are few.
                    if (counter < prev && delta > 8) {
                        log("⚠ ring REBOOTED (counter $prev -> $counter)")
                    } else {
                        total += delta
                    }
                }
                lastCounter = counter
            }

            state.value = RingState(
                kind, name, result.device.address, result.rssi, counter, total,
                SystemClock.elapsedRealtime(),
            )
        }

        override fun onScanFailed(errorCode: Int) { log("scan failed: $errorCode") }
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (scanning) return
        val scanner = context.getSystemService(BluetoothManager::class.java)
            .adapter?.bluetoothLeScanner
        if (scanner == null) { log("No BLE scanner (Bluetooth off?)"); return }
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        scanning = true
        scope.launch {
            // Expire to NONE when the advertisement stops (failsafe advertises
            // intermittently — going quiet IS information: "press the button").
            while (isActive) {
                delay(3_000)
                val s = state.value
                if (s.kind != RingKind.NONE &&
                    SystemClock.elapsedRealtime() - s.lastSeenMs > STALE_MS) {
                    state.value = RingState(totalClicks = total)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        context.getSystemService(BluetoothManager::class.java)
            .adapter?.bluetoothLeScanner?.stopScan(cb)
    }

    private companion object {
        const val NAME_PART = "Pebble Index"
        const val CFW_COMPANY = 0xFFFF
        const val FAILSAFE_COMPANY = 0x0EEA
        const val STALE_MS = 15_000L
    }
}
