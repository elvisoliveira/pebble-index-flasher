package poc.ringclick

import android.content.Context
import com.wtlp.haversinesatellitelibrary.HaversineAdvertisement
import com.wtlp.haversinesatellitelibrary.HaversineDebugDelegate
import com.wtlp.haversinesatellitelibrary.HaversineDebugInfo
import com.wtlp.haversinesatellitelibrary.HaversineEnvironment
import com.wtlp.haversinesatellitelibrary.HaversineException
import com.wtlp.haversinesatellitelibrary.HaversineHacksDelegate
import com.wtlp.haversinesatellitelibrary.HaversinePermissionsDelegate
import com.wtlp.haversinesatellitelibrary.HaversineSatellite
import com.wtlp.haversinesatellitelibrary.HaversineSatelliteCacheType
import com.wtlp.haversinesatellitelibrary.HaversineSatelliteCacheableState
import com.wtlp.haversinesatellitelibrary.HaversineSatelliteId
import com.wtlp.haversinesatellitelibrary.HaversineSatelliteManager
import com.wtlp.haversinesatellitelibrary.HaversineUpdateDelegate
import com.wtlp.haversinesatellitelibrary.operations.HaversineCollectionTransferCallback
import com.wtlp.haversinesatellitelibrary.operations.TelestoStoredCollectionIndexes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SUOTA flasher (lifted from CfwFlashActivity). The raw Telesto PROGRAM path is a
 * no-op for a full image; the lib's built-in updater only serves the OFFICIAL
 * firmware from a fixed HTTPS URL. So we build the NATIVE HaversineSatelliteManager
 * with our own HaversineEnvironment, whose updateDelegate serves a LOCAL image. The
 * lib's production SUOTA then streams the full image.
 *
 * The ring MUST be in FAILSAFE to accept the flash (§3.4). We serve v99.0 so the lib
 * always wants to update; a `served` flag stops serving after one success (else it
 * re-flashes forever). Discovery is the lib's own scan — the failsafe advertises
 * intermittently, so press the ring button to refresh the advertisement.
 */
class Flasher(private val context: Context, private val log: (String) -> Unit) {

    /** true = flash completed successfully; false = failure/timeout. */
    suspend fun flash(image: ByteArray, timeoutMs: Long = 120_000): Boolean {
        if (image.size < 4 || (image[0].toInt() and 0xFF) != 0x70 ||
            (image[1].toInt() and 0xF0) != 0x50) {
            log("REFUSED: image is not AN-B-001 (0x70 0x5X)")
            return false
        }
        log("=== SUOTA flasher === image ${image.size} B, serving as v99.0 hw 11.0")

        val done = CompletableDeferred<Boolean>()
        val served = AtomicBoolean(false)

        val env = HaversineEnvironment(
            context.applicationContext,
            HaversineEnvironment.ApplicationHardwareVersion(11, 0),
        )
        env.cache = object : HaversineSatelliteCacheType {
            override fun fetchCachedState(id: HaversineSatelliteId): HaversineSatelliteCacheableState? = null
            override fun cacheState(state: HaversineSatelliteCacheableState, id: HaversineSatelliteId) {}
        }
        env.permissionsDelegate = object : HaversinePermissionsDelegate {
            override fun shouldHandleAdvertisement(advertisement: HaversineAdvertisement) = true
            override fun shouldHandleSatellite(satellite: HaversineSatellite) = true
            override fun shouldTransferCollections(satellite: HaversineSatellite) = false
        }
        env.debugDelegate = object : HaversineDebugDelegate {
            override fun handleHaversineDebugInfo(info: HaversineDebugInfo, satellite: HaversineSatellite) {}
            override fun shouldReadRxRSSI(satellite: HaversineSatellite) = false
            override fun handleRxRSSI(rssi: Float, satellite: HaversineSatellite) {}
        }
        env.hacksDelegate = object : HaversineHacksDelegate {
            override fun shouldWipeCollectionsBeforeTransfer(satellite: HaversineSatellite) = false
            override fun wipedCollectionsBeforeTransfer(satellite: HaversineSatellite) {}
        }
        env.transferDelegate = object : HaversineCollectionTransferCallback {
            override fun firstCollectionToTransferInRange(indexes: TelestoStoredCollectionIndexes, satellite: HaversineSatellite) = Int.MAX_VALUE
            override fun willTransferCollectionsInRange(indexes: TelestoStoredCollectionIndexes, satellite: HaversineSatellite) {}
            override fun collectionTransferDidFinish(data: ByteArray, count: Int, satellite: HaversineSatellite) {}
            override fun collectionTransferDidFail(error: HaversineException, count: Int, satellite: HaversineSatellite) {}
        }
        env.updateDelegate = object : HaversineUpdateDelegate {
            override fun getFirmwareUpdate(satellite: HaversineSatellite): HaversineEnvironment.FirmwareUpdate? {
                if (served.get()) return null   // one flash is enough — stop the re-flash loop
                log(">>> serving ${image.size} B image to ${satellite.id?.rawValue}")
                return HaversineEnvironment.FirmwareUpdate(99, 0, 11, 0, image)
            }
            override fun willUpdateFirmware(satellite: HaversineSatellite, update: HaversineEnvironment.FirmwareUpdate) {
                log(">>> FLASH STARTING (${update.data.size} B) — keep the ring awake")
            }
            override fun didUpdateFirmware(satellite: HaversineSatellite, update: HaversineEnvironment.FirmwareUpdate, success: Boolean) {
                log(">>> flash finished — success=$success")
                if (success) served.set(true)
                if (!done.isCompleted) done.complete(success)
            }
            override fun getSensorConfigUpdate(satellite: HaversineSatellite): HaversineEnvironment.SensorConfigUpdate? = null
        }

        val manager = HaversineSatelliteManager(context.applicationContext, env)
        manager.addDiscoveredSatelliteListener { s ->
            log("discovered: ${s.name} (${s.id?.rawValue})")
        }
        log("Scanning… press the ring button (a failsafe ring self-advertises).")
        try {
            manager.startScanning()
        } catch (e: Exception) {
            log("startScanning failed: $e")
            return false
        }

        val result = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
        try { manager.stopScanning() } catch (_: Exception) {}
        if (!result) log("=== flash not completed (timeout ${timeoutMs / 1000}s or failure)")
        return result
    }
}
