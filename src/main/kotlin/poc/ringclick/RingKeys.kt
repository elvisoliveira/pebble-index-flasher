package poc.ringclick

import android.content.Context

/**
 * One key per ring, by address.
 *
 * The ring hands its key out once, to the connection a click's burst brought in, and
 * forgets it on every reboot (secret.h in the firmware). The 16-bit id it advertises
 * says which key it currently holds — 0 for none — so the app can tell from the scan
 * alone whether what it stored for this address still fits, and pair again on the
 * next click when it does not.
 *
 * SharedPreferences in the app's private storage: nothing on the phone but this app
 * can read it, and the key protects a voice clip, not a bank. The address is the
 * identity because the ring advertises a fixed public one; two boards without an
 * address in OTP would share the SDK's default and collide here, which is a bench
 * problem, not a ring one.
 */
class RingKeys(context: Context) {

    class Entry(val id: Int, val key: ByteArray)

    private val prefs = context.getSharedPreferences("ring-keys", Context.MODE_PRIVATE)

    fun get(address: String): Entry? {
        val hex = prefs.getString(address, null) ?: return null
        val key = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        return Entry(idOf(key), key)
    }

    fun put(address: String, key: ByteArray) {
        prefs.edit().putString(address, key.joinToString("") { "%02x".format(it) }).apply()
    }

    /** True when the ring's advertised id is the key we hold — the fetch will decrypt. */
    fun matches(address: String, ringKeyId: Int?): Boolean =
        ringKeyId != null && ringKeyId != 0 && get(address)?.id == ringKeyId

    companion object {
        /** The id the ring derives from a key: block over a zero nonce, counter 0, first
         * two bytes little-endian, 0 mapped to 1 because 0 means "no key". */
        fun idOf(key: ByteArray): Int {
            val b = ChaCha20.block(key, ByteArray(ChaCha20.NONCE_LEN), 0)
            val id = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
            return if (id == 0) 1 else id
        }
    }
}
