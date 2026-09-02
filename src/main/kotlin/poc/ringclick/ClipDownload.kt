package poc.ringclick

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Downloads a voice clip from a CFW ring and turns it into a playable WAV.
 *
 * The ring records while its button is held and keeps the clip in RAM. Its
 * advertisement already says how many samples are waiting, so the app knows there is
 * audio before it connects — nothing here has to poll.
 *
 * Protocol, all of it:
 *
 *     app -> ring   control point write {0x01, chunk_lo, chunk_hi}
 *     ring -> app   control point notify {0x01, samples_lo, samples_hi}   "starting"
 *     ring -> app   audio notify, one chunk at a time
 *     ring -> app   control point notify {0x02}                          "done"
 *
 * Two things carry the weight. The MTU: at the 23-byte default a notification holds 20
 * bytes and a full clip needs 820 of them, while at 247 it holds 244 and needs 68 — so
 * the request is made first and the chunk size is derived from whatever was actually
 * granted, never assumed. And the ring paces itself on send confirmations, one chunk
 * each, so there is no flow control to implement on this side: chunks simply arrive.
 *
 * The clip is 4-bit IMA ADPCM. Decoding it is what makes it playable, and it is also
 * the only real check that the whole chain worked — a broken predictor sounds like
 * noise, and nothing short of listening would catch it.
 */
object ClipDownload {

    /** Bytes on the wire become a WAV, returned for the caller to keep. Null on failure. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // one code path for minSdk 31..36, as elsewhere in this app
    suspend fun fetch(context: Context, address: String, log: (String) -> Unit): ByteArray? {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null) { log("No Bluetooth"); return null }
        val device = adapter.getRemoteDevice(address)

        val ready = CompletableDeferred<BluetoothGatt?>()
        val descriptorWritten = ArrayDeque<CompletableDeferred<Unit>>()
        val finished = CompletableDeferred<Boolean>()
        val data = ArrayList<Byte>(32 * 1024)
        var expectedSamples = 0
        var mtu = 23

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // MTU before discovery: the size it settles on decides the chunk
                    // size we are about to ask for.
                    gatt.requestMtu(WANTED_MTU)
                } else {
                    if (!ready.isCompleted) ready.complete(null)
                    if (!finished.isCompleted) finished.complete(false)
                }
            }
            override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
                gatt.discoverServices()
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                ready.complete(if (status == BluetoothGatt.GATT_SUCCESS) gatt else null)
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) {
                descriptorWritten.removeFirstOrNull()?.complete(Unit)
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                val v = ch.value ?: return
                when (ch.uuid) {
                    CTRL_POINT_UUID -> when {
                        v.size >= 3 && v[0] == CMD_SEND -> {
                            expectedSamples = (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
                            log("Ring is sending $expectedSamples samples…")
                        }
                        v.isNotEmpty() && v[0] == CMD_DONE -> finished.complete(true)
                    }
                    AUDIO_UUID -> for (b in v) data.add(b)
                }
            }
        }

        log("Connecting to $address…")
        val gatt = device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
        if (connected == null) {
            log("FAILED: could not reach the ring's services.")
            gatt?.close()
            return null
        }

        val service = connected.getService(SERVICE_UUID)
        val ctrl = service?.getCharacteristic(CTRL_POINT_UUID)
        val audio = service?.getCharacteristic(AUDIO_UUID)
        if (ctrl == null || audio == null) {
            log("FAILED: this firmware has no audio characteristic — it predates recording.")
            connected.close()
            return null
        }

        // Android runs ONE GATT operation at a time and silently drops the rest, so the
        // two subscriptions have to be awaited in turn rather than fired together.
        for (ch in listOf(ctrl, audio)) {
            connected.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd == null) { log("FAILED: no CCCD on ${ch.uuid}"); connected.close(); return null }
            val done = CompletableDeferred<Unit>()
            descriptorWritten.addLast(done)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            connected.writeDescriptor(cccd)
            if (withTimeoutOrNull(OP_TIMEOUT_MS) { done.await() } == null) {
                log("FAILED: could not subscribe to ${ch.uuid}")
                connected.close()
                return null
            }
        }

        val chunk = (mtu - ATT_OVERHEAD).coerceIn(MIN_CHUNK, MAX_CHUNK)
        log("MTU $mtu — asking for $chunk-byte chunks.")
        ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ctrl.value = byteArrayOf(CMD_SEND, (chunk and 0xFF).toByte(), ((chunk shr 8) and 0xFF).toByte())
        connected.writeCharacteristic(ctrl)

        val ok = withTimeoutOrNull(TRANSFER_TIMEOUT_MS) { finished.await() } ?: false
        connected.close()

        if (!ok || expectedSamples == 0) {
            log("FAILED: transfer did not complete (${data.size} bytes received).")
            return null
        }
        val wanted = (expectedSamples + 1) / 2
        if (data.size < wanted) {
            log("FAILED: short transfer — ${data.size} of $wanted bytes.")
            return null
        }
        log("Received ${data.size} bytes. Decoding…")

        val pcm = decodeAdpcm(data.toByteArray(), expectedSamples)
        log("Decoded %.1f s of audio.".format(expectedSamples.toDouble() / SAMPLE_RATE))
        return wav(pcm, SAMPLE_RATE)
    }

    /**
     * IMA ADPCM, the exact mirror of the encoder on the ring: same tables, and the same
     * walk back up the step/2/4 ladder. The two states never talk to each other — they
     * only stay in step because both start at zero and follow the same rules — so a
     * mismatch here is not a small error, it is noise.
     */
    private fun decodeAdpcm(data: ByteArray, samples: Int): ShortArray {
        val out = ShortArray(samples)
        var predictor = 0
        var index = 0
        for (i in 0 until samples) {
            val b = data[i shr 1].toInt() and 0xFF
            val code = if (i and 1 == 0) b and 0x0F else b shr 4
            val step = STEP_TABLE[index]
            var delta = step shr 3
            if (code and 4 != 0) delta += step
            if (code and 2 != 0) delta += step shr 1
            if (code and 1 != 0) delta += step shr 2
            predictor = if (code and 8 != 0) predictor - delta else predictor + delta
            predictor = predictor.coerceIn(-32768, 32767)
            index = (index + INDEX_TABLE[code]).coerceIn(0, 88)
            out[i] = predictor.toShort()
        }
        return out
    }

    /** Canonical 44-byte header plus little-endian 16-bit mono samples. */
    private fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val out = java.io.ByteArrayOutputStream(44 + dataBytes)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }
        fun le16(v: Int) { out.write(v); out.write(v shr 8) }
        ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1)
        le32(rate); le32(rate * 2); le16(2); le16(16)
        ascii("data"); le32(dataBytes)
        for (s in pcm) le16(s.toInt())
        return out.toByteArray()
    }

    /**
     * MIC_SAMPLE_RATE_HZ on the ring, and it has to match or the pitch comes out wrong.
     *
     * The converter free-runs at about 11 kHz — whatever its conversion time makes it,
     * not a rate anyone chose — and the firmware averages that down to 8 kHz, which is
     * the telephone standard, plenty for voice, and fits 37% more recording in the same
     * buffer.
     */
    const val SAMPLE_RATE = 8000

    private const val WANTED_MTU = 247
    private const val ATT_OVERHEAD = 3
    private const val MIN_CHUNK = 20
    private const val MAX_CHUNK = 244
    private const val CMD_SEND: Byte = 0x01
    private const val CMD_DONE: Byte = 0x02
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val OP_TIMEOUT_MS = 5_000L
    private const val TRANSFER_TIMEOUT_MS = 120_000L

    private val SERVICE_UUID: UUID = UUID.fromString("18424398-7cbc-11e9-8f9e-2a86e4085a59")
    private val CTRL_POINT_UUID: UUID = UUID.fromString("2d86686a-53dc-25b3-0c4a-f0e10c8dee20")
    private val AUDIO_UUID: UUID = UUID.fromString("2d86686a-53dc-25b3-0c4a-f0e10c8dee21")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )
    private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
}
