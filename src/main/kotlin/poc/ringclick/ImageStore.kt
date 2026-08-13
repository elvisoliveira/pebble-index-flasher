package poc.ringclick

import android.content.Context
import org.json.JSONObject

/** Firmwares bundled in the APK (assets/) — fully offline app, no downloads. */
class ImageStore(private val context: Context) {

    val official: ByteArray by lazy { context.assets.open("official.bin").use { it.readBytes() } }
    val cfw: ByteArray by lazy { context.assets.open("cfw.bin").use { it.readBytes() } }

    private val versions: JSONObject by lazy {
        JSONObject(context.assets.open("versions.json").use { it.readBytes() }.decodeToString())
    }
    val officialVersion: String get() = versions.optString("official", "?")
    val cfwVersion: String get() = versions.optString("cfw", "?")
}
