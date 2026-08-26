package poc.ringclick

import android.annotation.SuppressLint
import android.content.Context
import com.wtlp.haversinesatellitelibrary.HaversineException
import com.wtlp.haversinesatellitelibrary.HaversineLinkController
import com.wtlp.haversinesatellitelibrary.HaversineSatellite
import com.wtlp.haversinesatellitelibrary.operations.HaversineOperationCollectingCallback
import com.wtlp.haversinesatellitelibrary.operations.HaversineOperationPriority
import com.wtlp.haversinesatellitelibrary.operations.PanicOperation
import com.wtlp.haversinesatellitelibrary.operations.TelestoInputParameters
import com.wtlp.haversinesatellitelibrary.operations.TelestoOperation
import com.wtlp.haversinesatellitelibrary.operations.TelestoOperationType
import com.wtlp.haversinesatellitelibrary.operations.TelestoRequest
import coredevices.haversine.CollectionIndexStorage
import coredevices.haversine.KMPHaversineDebugDelegate
import coredevices.haversine.KMPHaversineDebugInfo
import coredevices.haversine.KMPHaversineHacksDelegate
import coredevices.haversine.KMPHaversineSatellite
import coredevices.haversine.KMPHaversineSatelliteManager
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Telesto memory operations (lifted from DumpActivity). The OFFICIAL ring speaks
 * Telesto; the CFW does not. Used only for official -> failsafe: invalidate the
 * validflag + reset.
 *
 * Inviolable rules (PLAN §3.2 / §8):
 *   - Telesto ops on the MAIN thread only (the native layer delivers on the main
 *     looper; dispatching from background aborts the process);
 *   - reach the ring by its ADVERTISED address, never the bonded one (failsafe does
 *     not bond and advertises a different address);
 *   - a dropped link after a reset/panic is SUCCESS;
 *   - NEVER write 0x40050000 / 0x40060001<0x4B80 (failsafe = recovery net). Here we
 *     only write 1 byte to the primary's validflag.
 */
class TelestoOps(private val context: Context, private val log: (String) -> Unit) {

    /** Primary record; validflag at offset 2 (0xAA=valid, 0x00=invalid/failsafe). */
    private val primary = 0x40060000
    private val validflagOffset = 2

    /**
     * official -> failsafe: writes 0x00 to the validflag (0xAA->0x00, bit-clear, no
     * erase) and resets. The ring comes back up in failsafe. Verify with a re-scan.
     */
    suspend fun invalidatePrimaryAndReset(address: String): Boolean = withLink(address) { link ->
        log("--- invalidating validflag: PROGRAM 0x00 at 0x%08X off %d".format(primary, validflagOffset))
        val before = read(link, primary, validflagOffset, 1)
        log("    before = ${preview(before)}")
        val r = telesto(link, TelestoOperationType.TELESTO_PROGRAM_MEMORY,
            primary, validflagOffset, 1, byteArrayOf(0x00))
        if (r == null) { log("    PROGRAM failed — image intact, safe to retry"); return@withLink false }
        delay(1_000)
        val after = read(link, primary, validflagOffset, 1)
        log("    after = ${preview(after)}")
        if (after?.firstOrNull()?.toInt()?.and(0xFF) != 0x00) {
            log("    validflag did NOT clear — aborting reset"); return@withLink false
        }
        log("--- RESET (the ring comes back up in failsafe; the link drops)")
        sendReset(link)
        true
    } ?: false

    // --- infrastructure (main-thread dispatch, reflection, bring-up) ---

    private suspend fun read(link: HaversineLinkController, address: Int, offset: Int, length: Int): ByteArray? =
        telesto(link, TelestoOperationType.TELESTO_READ_MEMORY, address, offset, length, ByteArray(0))

    private fun timeoutFor(bytes: Int): Long = 30_000L + ((bytes + 173) / 174) * 500L

    /**
     * Queues the op; it runs on the next connection window. MUST run on the main
     * thread: the native layer latches the calling thread's JNIEnv and delivers on
     * the main looper — dispatching from background aborts the process inside
     * receiveTelestoDataBytes.
     */
    private suspend fun telesto(
        link: HaversineLinkController, type: TelestoOperationType,
        address: Int, offset: Int, length: Int, data: ByteArray,
    ): ByteArray? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutFor(maxOf(length, data.size))) {
            suspendCancellableCoroutine { cont ->
                val request = TelestoRequest(type, address, offset, length)
                val operation = TelestoOperation(TelestoInputParameters(request, data))
                val canceler = link.performOperation(
                    operation, HaversineOperationPriority.HIGHEST,
                    object : HaversineOperationCollectingCallback() {
                        override fun handleCompletionWithCollectedData(error: HaversineException?, result: ByteArray?) {
                            if (error != null) log("  ERROR: code=${error.code} — ${error.description}")
                            if (cont.isActive) cont.resume(if (error != null) null else (result ?: ByteArray(0)))
                        }
                    },
                )
                cont.invokeOnCancellation { canceler.cancel() }
            }
        }
    }

    /** Reset via PanicOperation (type 0xc). The ring reboots and drops the link — a timeout here is EXPECTED. */
    private suspend fun sendReset(link: HaversineLinkController) {
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val canceler = link.performOperation(
                        PanicOperation(), HaversineOperationPriority.HIGHEST,
                        object : HaversineOperationCollectingCallback() {
                            override fun handleCompletionWithCollectedData(error: HaversineException?, result: ByteArray?) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        },
                    )
                    cont.invokeOnCancellation { canceler.cancel() }
                }
            }
        }
    }

    /** The satellite holds its link controller in a private field; the API never exposes it. */
    private fun linkControllerOf(satellite: HaversineSatellite): HaversineLinkController? = try {
        HaversineSatellite::class.java.getDeclaredField("linkController")
            .apply { isAccessible = true }
            .get(satellite) as HaversineLinkController
    } catch (e: Exception) {
        log("reflection failed: $e"); null
    }

    /**
     * Creates a satellite manager, DRIVES DISCOVERY, hands [block] the ring's link
     * controller, then tears the whole thing down (scan + manager scope).
     *
     * The catch that broke official -> failsafe: `getSatelliteById` is only a ONE-SHOT
     * `wrap.retrieveSatellite(id)`, which throws ("No cached state ... cannot reconstruct
     * without discovery") the instant it runs if discovery has not cached the ring yet —
     * it does NOT wait. And `startScanning` is a COLD flow: the native scan runs only
     * while it is collected. So we launch a collector to drive discovery AND retry
     * getSatelliteById until the cache is populated (or 120 s elapse).
     *
     * Skipping the scan was meant to dodge the lib's auto-update, but that is moot: the
     * app has no INTERNET permission, so the update job fails on DNS (seen in logcat).
     * Discovery here only finds the ring; it never programs it.
     */
    @SuppressLint("MissingPermission")
    private suspend fun <T> withLink(address: String, block: suspend (HaversineLinkController) -> T): T? {
        val id = address.replace(":", "")
        // SupervisorJob: the lib's offline update job fails on DNS; don't let it cancel us.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = KMPHaversineSatelliteManager(
            pairedSatelliteIdProvider = { id },
            debugDelegate = object : KMPHaversineDebugDelegate {
                override fun handleHaversineDebugInfo(info: KMPHaversineDebugInfo) {}
                override fun shouldReadRxRSSI(satellite: KMPHaversineSatellite) = false
                override fun handleRxRSSI(rssi: Float, satellite: KMPHaversineSatellite) {}
            },
            hacksDelegate = object : KMPHaversineHacksDelegate {
                override fun shouldWipeCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) = false
                override fun wipedCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) {}
            },
            // In-memory stub: this app never syncs collections, the cursor is never read back.
            collectionIndexStorage = object : CollectionIndexStorage {
                private val flow = MutableStateFlow<Int?>(null)
                override val lastSuccessfulCollectionIndex: StateFlow<Int?> = flow
                override fun setLastSuccessfulCollectionIndex(index: Int?) { flow.value = index }
            },
            context = context.applicationContext,
            hwVersion = Pair(11, 0),
            scope,
        )
        try {
            manager.awaitBluetoothReady()
            // Cold flow: collecting it starts the native scan; cancelling the scope stops it.
            scope.launch {
                try { manager.startScanning().collect { } }
                catch (_: CancellationException) { /* expected: finally cancels the scope */ }
                catch (e: Exception) { log("scan ended: $e") }
            }
            log("Opening link with $address (scanning; no auto-update)…")
            // Retry until discovery caches the ring (getSatelliteById throws until then).
            val satellite = withTimeoutOrNull(120_000) {
                var s: KMPHaversineSatellite? = null
                while (s == null) {
                    s = try { manager.getSatelliteById(id) } catch (_: Exception) { null }
                    if (s == null) delay(1_500)
                }
                s
            } ?: run {
                log("FAILED: satellite did not appear within 120s — press the ring button"); return null
            }
            val link = linkControllerOf(satellite.wrap) ?: run {
                log("FAILED: linkController unreachable via reflection"); return null
            }
            log("Satellite ${satellite.id} ready; link = ${link.state}")
            return block(link)
        } finally {
            scope.cancel()   // stop scanning + tear down the manager's coroutines (no leak)
        }
    }

    private fun preview(data: ByteArray?): String =
        if (data == null) "<null>" else data.joinToString(" ") { "%02X".format(it) }
}
