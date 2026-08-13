package poc.ringclick

import android.content.Context
import org.json.JSONObject

/** CFW image bundled in the APK (assets/) — fully offline app, no downloads. */
class ImageStore(private val context: Context) {

    val cfw: ByteArray by lazy { context.assets.open("cfw.bin").use { it.readBytes() } }

    val cfwVersion: String by lazy {
        JSONObject(context.assets.open("versions.json").use { it.readBytes() }.decodeToString())
            .optString("cfw", "?")
    }
}
