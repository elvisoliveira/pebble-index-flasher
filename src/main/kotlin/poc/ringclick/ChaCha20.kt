package poc.ringclick

/**
 * ChaCha20, RFC 8439: 256-bit key, 96-bit nonce, 32-bit block counter. The one
 * primitive the ring's key model uses, in both directions:
 *
 *   - the clip arrives XOR'd with the keystream from counter 0 (ClipDownload)
 *   - a tagged command carries the first 16 bytes of the block over its nonce with
 *     the OPCODE as the counter (CfwControl.enterFailsafe)
 *   - the key id the ring advertises is the first two bytes of the block over a
 *     zero nonce, counter 0 (RingKeys.idOf)
 *
 * Written out rather than taken from javax.crypto: Android's "ChaCha20" Cipher is
 * documented from API 28, but which parameter spec its provider accepts, and whether
 * the block counter can be set at all, is not — and a wrong guess there decrypts to
 * noise on a phone, not on this machine. Thirty lines that mirror the ring's own
 * src/chacha.c line for line, checked against the RFC's test vector at class load.
 */
object ChaCha20 {

    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16

    /** XOR [data] in place with the keystream starting at block [counter]. */
    fun xor(key: ByteArray, nonce: ByteArray, counter: Int, data: ByteArray) {
        var c = counter
        var i = 0
        while (i < data.size) {
            val ks = block(key, nonce, c++)
            val n = minOf(64, data.size - i)
            for (j in 0 until n) data[i + j] = (data[i + j].toInt() xor ks[j].toInt()).toByte()
            i += n
        }
    }

    /** The command tag: block over the nonce with the opcode as the counter, truncated. */
    fun tag(key: ByteArray, nonce: ByteArray, opcode: Int): ByteArray =
        block(key, nonce, opcode).copyOf(TAG_LEN)

    fun block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        require(key.size == KEY_LEN && nonce.size == NONCE_LEN)
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, 4 * i)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le32(nonce, 4 * i)
        val x = s.copyOf()
        repeat(10) {
            qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13); qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15)
            qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12); qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = x[i] + s[i]
            out[4 * i] = v.toByte(); out[4 * i + 1] = (v shr 8).toByte()
            out[4 * i + 2] = (v shr 16).toByte(); out[4 * i + 3] = (v shr 24).toByte()
        }
        return out
    }

    private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    init {
        /* RFC 8439 §2.3.2, key 00..1f, nonce 00 00 00 09 00 00 00 4a 00 00 00 00, counter 1.
         * Not a unit test: a guard that turns "every clip decodes to noise" into a crash
         * at the first use, with the reason in the trace. */
        val key = ByteArray(32) { it.toByte() }
        val nonce = byteArrayOf(0, 0, 0, 9, 0, 0, 0, 0x4a, 0, 0, 0, 0)
        val want = byteArrayOf(0x10, 0xf1.toByte(), 0xe7.toByte(), 0xe4.toByte(), 0xd1.toByte(), 0x3b, 0x59, 0x15,
                               0x50, 0x0f, 0xdd.toByte(), 0x1f, 0xa3.toByte(), 0x20, 0x71, 0xc4.toByte())
        check(block(key, nonce, 1).copyOf(16).contentEquals(want)) { "ChaCha20 does not match RFC 8439" }
    }
}
