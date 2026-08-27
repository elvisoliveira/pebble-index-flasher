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
 * (PLAN §3.1). Match by name substring ("Pebble Index"); the state comes purely from
 * the manufacturer data (captured on a real ring — see the flasher wiki, "Telling the
 * firmware apart"):
 *   CFW      -> mfr company 0xFFFF (payload[0] = click counter)
 *   FAILSAFE -> mfr company 0x0EEA, payload starts with AD DE AD DE
 *   OFFICIAL -> mfr company 0x0EEA, payload starts with FF FF
 * Both stock states are matched positively by their payload signature; anything else is
 * ignored (NONE), never guessed.
 *
 * The name and the advertised service UUID are NOT reliable: official and failsafe
 * share both (same device-specific "Pebble Index <suffix>" and the same 0x0EEA company
 * id) — only the payload signature tells them apart. See classify().
 *
 * No ScanFilter: the native filter only matches names exactly and we need a substring.
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
            val kind = classify(cfwMfr, rec.getManufacturerSpecificData(STOCK_COMPANY))
            // Unrecognised advertisement: ignore it rather than clobber a good state.
            // A real ring keeps advertising; the stale timer drops to NONE if it stops.
            if (kind == RingKind.NONE) return

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
        if (!openScan()) { log("No BLE scanner (Bluetooth off?)"); return }
        scanning = true
        scope.launch {
            // Expire to NONE when the advertisement stops (failsafe advertises
            // intermittently — going quiet IS information: "press the button").
            while (isActive) {
                delay(500)
                val s = state.value
                if (s.kind != RingKind.NONE &&
                    SystemClock.elapsedRealtime() - s.lastSeenMs > STALE_MS) {
                    state.value = RingState(totalClicks = total)
                }
            }
        }

        // Auto-heal: Android silently stops delivering scan results after a fixed window
        // (~10 min measured on a Pixel 7a — results just stop, foreground, no onScanFailed).
        // Re-issuing startScan opens a fresh window, so restart well inside it. ~1 start per
        // 4 min is far under the framework's 5-starts-per-30-s limit.
        // ponytail: fixed-interval proactive restart; if some OEM's window is < RESTART_MS,
        //           switch to a reactive restart keyed on "no adverts seen for N s".
        scope.launch {
            while (isActive) {
                delay(RESTART_MS)
                closeScan()
                openScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openScan(): Boolean {
        val s = context.getSystemService(BluetoothManager::class.java)
            .adapter?.bluetoothLeScanner ?: return false
        s.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        return true
    }

    @SuppressLint("MissingPermission")
    private fun closeScan() {
        context.getSystemService(BluetoothManager::class.java)
            .adapter?.bluetoothLeScanner?.stopScan(cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        closeScan()
    }

    companion object {
        private const val RESTART_MS = 4L * 60 * 1000   // proactive scan restart, inside the ~10 min OS window
        const val NAME_PART = "Pebble Index"
        const val CFW_COMPANY = 0xFFFF      // CFW click beacon; official/failsafe use 0x0EEA
        const val STOCK_COMPANY = 0x0EEA    // shared by official AND failsafe
        private const val STALE_MS = 1_000L   // burst mode: track the ~3s burst closely

        // Both stock firmwares carry a signature in the first bytes of their 0x0EEA
        // payload, and the signatures are mutually exclusive (0xAD vs 0xFF at byte 0):
        //   FAILSAFE_MARKER — the boot-attempt-counter "deadhead", a firmware constant
        //     confirmed against the flash marker documented in the firmware wiki.
        //   OFFICIAL_MARKER — the captured ring's official payload is FF FF B9 D8 XX YY,
        //     where the last two bytes change between advertisements (a live counter) and
        //     FF FF B9 D8 stays constant. Matching the FF FF prefix is deliberate — the
        //     full payload varies. ponytail: one physical ring, so whether B9 D8 is
        //     device-specific is unverified; FF FF looks like a fixed tag. If a second
        //     ring's official payload does not start with FF FF, widen this signature.
        private val FAILSAFE_MARKER = byteArrayOf(0xAD.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0xDE.toByte())
        private val OFFICIAL_MARKER = byteArrayOf(0xFF.toByte(), 0xFF.toByte())

        /**
         * Pure classifier over the two manufacturer-data payloads. Both stock states are
         * matched *positively* by their 0x0EEA signature; a 0x0EEA payload matching
         * neither (or no recognised beacon at all) returns NONE — the caller ignores it
         * rather than guessing a state and offering a destructive action on it.
         */
        fun classify(cfwMfr: ByteArray?, stockMfr: ByteArray?): RingKind = when {
            cfwMfr != null -> RingKind.CFW
            stockMfr == null -> RingKind.NONE
            stockMfr.startsWith(FAILSAFE_MARKER) -> RingKind.FAILSAFE
            stockMfr.startsWith(OFFICIAL_MARKER) -> RingKind.OFFICIAL
            else -> RingKind.NONE
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
            if (size < prefix.size) return false
            for (i in prefix.indices) if (this[i] != prefix[i]) return false
            return true
        }
    }
}
