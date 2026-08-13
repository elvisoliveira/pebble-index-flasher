package poc.ringclick

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GATT client for the CFW control point (lifted from CfwCtrlActivity). A running
 * CFW does NOT speak Telesto — this is the BLE path back to failsafe.
 *
 * The ring RESETS inside the write handler, so the link drops and no write callback
 * arrives — a dropped link is SUCCESS, not failure (PLAN §3.2). The real
 * confirmation is a re-scan showing "Pebble Index FBA" (OperationRunner).
 *
 * ponytail: enterFailsafe only. The 0x01 opcode (raw 1-byte poke) is left out —
 * the low-level escape hatch stays in the Python tooling (ctrl_write.py).
 */
object CfwControl {

    /** Sends [0x00] — the firmware's enter_failsafe() validates the header before writing. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // value+writeCharacteristic(char): fine from minSdk 31 to 36, one path
    suspend fun enterFailsafe(context: Context, address: String, log: (String) -> Unit): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null) { log("No Bluetooth"); return false }
        val device: BluetoothDevice = adapter.getRemoteDevice(address)

        val ready = CompletableDeferred<BluetoothGatt?>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                else if (!ready.isCompleted) ready.complete(null)
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                ready.complete(if (status == BluetoothGatt.GATT_SUCCESS) gatt else null)
            }
        }

        log("Connecting to $address…")
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
        if (connected == null) {
            log("FAILED: no service discovery (link dropped or timed out)")
            gatt?.close()
            return false
        }

        val ch = connected.getService(SERVICE_UUID)?.getCharacteristic(CTRL_POINT_UUID)
        if (ch == null) {
            log("FAILED: control point missing — this device is not running the CFW.")
            connected.close()
            return false
        }

        // Write without response: the ring resets inside the handler; an acknowledged
        // write would just wait for a reply that can never come.
        log("Sending 0x00 (enter_failsafe) to the control point…")
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = byteArrayOf(0x00)
        val queued = connected.writeCharacteristic(ch)
        log(if (queued) "  write queued — the ring should reset" else "  write REJECTED by the stack")

        delay(2_000)   // let the reset land before we tear the link down
        connected.close()
        return queued
    }

    private const val CONNECT_TIMEOUT_MS = 30_000L
    private val SERVICE_UUID: UUID = UUID.fromString("18424398-7cbc-11e9-8f9e-2a86e4085a59")
    private val CTRL_POINT_UUID: UUID = UUID.fromString("2d86686a-53dc-25b3-0c4a-f0e10c8dee20")
}
