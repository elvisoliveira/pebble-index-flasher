package poc.ringclick

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The recordings kept on the phone.
 *
 * Files are the whole database. The name carries the moment it was taken and the size
 * carries the length, so there is no index to keep in step with the directory and
 * nothing to repair when the two disagree — because they cannot.
 *
 * The ring holds a clip in RAM until it is fetched, and a reset loses it, so the phone
 * is where a recording actually becomes durable.
 */
class ClipStore(context: Context) {

    private val dir = File(context.filesDir, "clips").apply { mkdirs() }

    /** Writes a WAV under the current time. Returns the file it created. */
    fun save(wav: ByteArray): File {
        val file = File(dir, "clip-${System.currentTimeMillis()}.wav")
        file.writeBytes(wav)
        return file
    }

    fun delete(file: File) { file.delete() }

    fun clear() { list().forEach { it.delete() } }

    /** Newest first — the one just recorded is the one being looked for. */
    fun list(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedByDescending { stamp(it) } ?: emptyList()

    companion object {
        private val LABEL_FORMAT = SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault())

        fun stamp(file: File): Long =
            file.name.removePrefix("clip-").removeSuffix(".wav").toLongOrNull() ?: file.lastModified()

        /** Seconds of audio, from the file size: 44 bytes of header, then 16-bit mono. */
        fun seconds(file: File): Double =
            ((file.length() - WAV_HEADER) / 2).coerceAtLeast(0) .toDouble() / ClipDownload.SAMPLE_RATE

        fun label(file: File): String =
            "%s  ·  %.1f s".format(LABEL_FORMAT.format(Date(stamp(file))), seconds(file))

        private const val WAV_HEADER = 44L
    }
}
