package poc.ringclick

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GATT client for the CFW control point. A running CFW does NOT speak Telesto — this
 * is the BLE path back to failsafe, and the place the ring's key is fetched from.
 *
 * Two operations, one connection each:
 *
 *   pair            write {0x10}, receive {0x10, key[32]} on the control point. The
 *                   ring answers only the connection a click's burst brought in, and
 *                   only while the control point is subscribed — so subscribe first,
 *                   and expect silence when the burst was not a click's.
 *   enterFailsafe   write {0x00, nonce[12], tag[16]}, tag = ChaCha20(key, nonce,
 *                   counter = 0x00)[0..16]. The ring RESETS inside the write handler,
 *                   so the link drops and no write callback arrives — a dropped link is
 *                   SUCCESS, not failure. The real confirmation is a re-scan classifying
 *                   the ring as FAILSAFE — its 0x0EEA advertisement payload now begins
 *                   with the AD DE AD DE marker (OperationRunner; see
 *                   RingScanner.classify). Without a key for this ring the command is
 *                   not even sent: the ring would ignore it.
 */
object CfwControl {

    /** Asks the ring for its key and stores it. False when the ring did not answer. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // value+writeCharacteristic(char): fine from minSdk 31 to 36, one path
    suspend fun pair(context: Context, address: String, keys: RingKeys, log: (String) -> Unit): Boolean {
        val answer = CompletableDeferred<ByteArray?>()
        val descriptorWritten = CompletableDeferred<Unit>()
        val gatt = connect(context, address, log, object : Hooks {
            override fun onDescriptorWrite() { descriptorWritten.complete(Unit) }
            override fun onNotify(ch: BluetoothGattCharacteristic, v: ByteArray) {
                if (ch.uuid == CTRL_POINT_UUID && v.size == 1 + ChaCha20.KEY_LEN && v[0] == OP_PAIR) {
                    answer.complete(v.copyOfRange(1, v.size))
                }
            }
            override fun onDropped() { answer.complete(null) }
        }) ?: return false

        val ctrl = gatt.getService(SERVICE_UUID)?.getCharacteristic(CTRL_POINT_UUID)
        if (ctrl == null) {
            log("FAILED: control point missing — this device is not running the CFW.")
            gatt.close(); return false
        }
        gatt.setCharacteristicNotification(ctrl, true)
        val cccd = ctrl.getDescriptor(CCCD_UUID)
        if (cccd == null) { log("FAILED: no CCCD on the control point"); gatt.close(); return false }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(cccd)
        if (withTimeoutOrNull(OP_TIMEOUT_MS) { descriptorWritten.await() } == null) {
            log("FAILED: could not subscribe to the control point"); gatt.close(); return false
        }

        log("Asking the ring for its key…")
        ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ctrl.value = byteArrayOf(OP_PAIR)
        gatt.writeCharacteristic(ctrl)
        val key = withTimeoutOrNull(OP_TIMEOUT_MS) { answer.await() }
        gatt.close()
        if (key == null) {
            log("No key: the ring only pairs on the connection a click brought in. Click and try again.")
            return false
        }
        keys.put(address, key)
        log("Paired — key id %d.".format(RingKeys.idOf(key)))
        return true
    }

    /** Sends the tagged failsafe command — the firmware's enter_failsafe() validates the header before writing. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun enterFailsafe(context: Context, address: String, keys: RingKeys, log: (String) -> Unit): Boolean {
        val entry = keys.get(address)
        if (entry == null) {
            log("FAILED: no key for this ring. Click it once so the app can pair, then try again.")
            return false
        }
        val gatt = connect(context, address, log, object : Hooks {}) ?: return false
        val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CTRL_POINT_UUID)
        if (ch == null) {
            log("FAILED: control point missing — this device is not running the CFW.")
            gatt.close(); return false
        }

        val nonce = ByteArray(ChaCha20.NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cmd = byteArrayOf(OP_FAILSAFE) + nonce + ChaCha20.tag(entry.key, nonce, OP_FAILSAFE.toInt())
        // Write without response: the ring resets inside the handler; an acknowledged
        // write would just wait for a reply that can never come.
        log("Sending 0x00 (enter_failsafe) to the control point…")
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = cmd
        val queued = gatt.writeCharacteristic(ch)
        log(if (queued) "  write queued — the ring should reset" else "  write REJECTED by the stack")

        delay(2_000)   // let the reset land before we tear the link down
        gatt.close()
        return queued
    }

    private interface Hooks {
        fun onDescriptorWrite() {}
        fun onNotify(ch: BluetoothGattCharacteristic, v: ByteArray) {}
        fun onDropped() {}
    }

    /** Connect and discover; null (and a log line) when the ring is not reachable. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun connect(context: Context, address: String, log: (String) -> Unit, hooks: Hooks): BluetoothGatt? {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null) { log("No Bluetooth"); return null }
        val device: BluetoothDevice = adapter.getRemoteDevice(address)

        val ready = CompletableDeferred<BluetoothGatt?>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                else { if (!ready.isCompleted) ready.complete(null); hooks.onDropped() }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                ready.complete(if (status == BluetoothGatt.GATT_SUCCESS) gatt else null)
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) { hooks.onDescriptorWrite() }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                ch.value?.let { hooks.onNotify(ch, it) }
            }
        }

        log("Connecting to $address…")
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
        if (connected == null) {
            log("FAILED: no service discovery (link dropped or timed out)")
            gatt?.close()
        }
        return connected
    }

    private const val OP_FAILSAFE: Byte = 0x00
    private const val OP_PAIR: Byte = 0x10
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val OP_TIMEOUT_MS = 5_000L
    private val SERVICE_UUID: UUID = UUID.fromString("18424398-7cbc-11e9-8f9e-2a86e4085a59")
    private val CTRL_POINT_UUID: UUID = UUID.fromString("2d86686a-53dc-25b3-0c4a-f0e10c8dee20")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
