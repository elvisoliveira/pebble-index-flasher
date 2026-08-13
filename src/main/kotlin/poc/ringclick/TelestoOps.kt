package poc.ringclick

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Cursor of the last synced collection; the lib advances it on its own. */
class PrefsIndexStorage(context: Context) : CollectionIndexStorage {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val stateFlow = MutableStateFlow(
        if (prefs.contains("lastIndex")) prefs.getInt("lastIndex", 0) else null
    )
    override val lastSuccessfulCollectionIndex: StateFlow<Int?> = stateFlow
    override fun setLastSuccessfulCollectionIndex(index: Int?) {
        prefs.edit().apply {
            if (index == null) remove("lastIndex") else putInt("lastIndex", index)
        }.apply()
        stateFlow.value = index
    }
}

/**
 * Telesto memory operations (lifted from DumpActivity). The OFFICIAL ring speaks
 * Telesto; the CFW does not. Used only to:
 *   - official -> failsafe: invalidate the validflag + reset;
 *   - read the validflag (verification) and the bootlog (brick risk).
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
    private val bootlog = 0x40060002   // event log, physical 0x1F000

    /** Reads the primary's validflag. 0xAA=a valid image booted, 0x00=failsafe, null=failure. */
    suspend fun readValidflag(address: String): Int? {
        val link = bringUp(address) ?: return null
        val d = read(link, primary, validflagOffset, 1)
        return d?.firstOrNull()?.toInt()?.and(0xFF)
    }

    /**
     * Reads the first bytes of the event log (boot-attempt count). Clean state
     * = AD DE AD DE (the 0xDEADDEAD magic written by the CFW on a healthy boot); the
     * failsafe stamps a slot per boot and after 4 refuses the primary (§3.7 #4).
     */
    suspend fun readBootlog(address: String): ByteArray? {
        val link = bringUp(address) ?: return null
        return read(link, bootlog, 0, 64)
    }

    /**
     * official -> failsafe: writes 0x00 to the validflag (0xAA->0x00, bit-clear, no
     * erase) and resets. The ring comes back up in failsafe. Verify with a re-scan.
     */
    suspend fun invalidatePrimaryAndReset(address: String): Boolean {
        val link = bringUp(address) ?: return false
        log("--- invalidating validflag: PROGRAM 0x00 at 0x%08X off %d".format(primary, validflagOffset))
        val before = read(link, primary, validflagOffset, 1)
        log("    before = ${preview(before)}")
        val r = telesto(link, TelestoOperationType.TELESTO_PROGRAM_MEMORY,
            primary, validflagOffset, 1, byteArrayOf(0x00))
        if (r == null) { log("    PROGRAM failed — image intact, safe to retry"); return false }
        delay(1_000)
        val after = read(link, primary, validflagOffset, 1)
        log("    after = ${preview(after)}")
        if (after?.firstOrNull()?.toInt()?.and(0xFF) != 0x00) {
            log("    validflag did NOT clear — aborting reset"); return false
        }
        log("--- RESET (the ring comes back up in failsafe; the link drops)")
        sendReset(link)
        return true
    }

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
     * Brings the satellite up by its ADVERTISED address via getSatelliteById (without
     * the scan/sync loop that triggers the lib's auto-update) and returns its
     * linkController.
     */
    @SuppressLint("MissingPermission")
    private suspend fun bringUp(address: String): HaversineLinkController? {
        val manager = KMPHaversineSatelliteManager(
            pairedSatelliteIdProvider = { address.replace(":", "") },
            debugDelegate = object : KMPHaversineDebugDelegate {
                override fun handleHaversineDebugInfo(info: KMPHaversineDebugInfo) {}
                override fun shouldReadRxRSSI(satellite: KMPHaversineSatellite) = false
                override fun handleRxRSSI(rssi: Float, satellite: KMPHaversineSatellite) {}
            },
            hacksDelegate = object : KMPHaversineHacksDelegate {
                override fun shouldWipeCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) = false
                override fun wipedCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) {}
            },
            collectionIndexStorage = PrefsIndexStorage(context.applicationContext),
            context = context.applicationContext,
            hwVersion = Pair(11, 0),
            CoroutineScope(Dispatchers.Default),
        )
        manager.awaitBluetoothReady()
        log("Opening link with $address (no auto-update)…")
        val satellite = withTimeoutOrNull(120_000) {
            manager.getSatelliteById(address.replace(":", ""))
        } ?: run {
            log("FAILED: satellite did not appear within 120s — press the ring button"); return null
        }
        val link = linkControllerOf(satellite.wrap) ?: run {
            log("FAILED: linkController unreachable via reflection"); return null
        }
        log("Satellite ${satellite.id} ready; link = ${link.state}")
        return link
    }

    private fun preview(data: ByteArray?): String =
        if (data == null) "<null>" else data.joinToString(" ") { "%02X".format(it) }
}
