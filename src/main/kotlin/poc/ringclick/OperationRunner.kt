package poc.ringclick

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * State machine (PLAN §3.5). Given the current state and a target, it runs the
 * right chain, drives the discovery prompts ("press the button") and verifies each
 * step by re-scan. Hides the §3.7 gotchas.
 *
 *   official ──(invalidate validflag + reset, Telesto)──► failsafe
 *   failsafe ──(SUOTA cfw.bin)──► CFW      failsafe ──(SUOTA official.bin)──► official
 *   CFW ──(GATT 0x00)──► failsafe
 */
class OperationRunner(
    private val context: Context,
    private val scanner: RingScanner,
    private val images: ImageStore,
    private val log: (String) -> Unit,
) {
    private val flasher = Flasher(context, log)
    private val telesto = TelestoOps(context, log)

    /** CFW -> failsafe via the control point, confirmed by re-scan. */
    suspend fun enterFailsafe(): Boolean {
        val s = scanner.state.value
        if (s.kind != RingKind.CFW || s.address == null) {
            log("'Enter failsafe' is only possible from CFW."); return false
        }
        if (!CfwControl.enterFailsafe(context, s.address, log)) return false
        return verify(RingKind.FAILSAFE)
    }

    /** Flash the CFW. From OFFICIAL: invalidate -> failsafe -> SUOTA. From FAILSAFE: SUOTA directly. */
    suspend fun flashCfw(): Boolean {
        val s = scanner.state.value
        when (s.kind) {
            RingKind.CFW -> { log("The ring already runs the CFW."); return true }
            RingKind.OFFICIAL -> {
                if (s.address == null) { log("No ring address."); return false }
                log("=== official -> failsafe (invalidate + reset) ===")
                if (!telesto.invalidatePrimaryAndReset(s.address)) return false
                if (!verify(RingKind.FAILSAFE)) return false
            }
            RingKind.FAILSAFE -> {}
            RingKind.NONE -> { log("Ring not found. Press the button and bring it closer to the phone."); return false }
        }
        log("=== SUOTA cfw.bin (${images.cfwVersion}) ===")
        if (!flasher.flash(images.cfw)) return false
        return verify(RingKind.CFW)
    }

    /** failsafe -> official via SUOTA. */
    suspend fun restoreOfficial(): Boolean {
        val s = scanner.state.value
        if (s.kind != RingKind.FAILSAFE) {
            log("'Restore official' is only possible from failsafe (official is the factory one)."); return false
        }
        log("=== SUOTA official.bin (${images.officialVersion}) ===")
        if (!flasher.flash(images.official)) return false
        return verify(RingKind.OFFICIAL)
    }

    /** Wait for the scan to show the expected state (the ring rebooted into another one). */
    private suspend fun verify(expected: RingKind): Boolean {
        log("Verifying… press the ring button to refresh the advertisement.")
        val ok = withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
            scanner.state.first { it.kind == expected }
            true
        } ?: false
        log(if (ok) "✔ ring now in $expected" else "✘ did not confirm $expected within ${VERIFY_TIMEOUT_MS / 1000}s")
        return ok
    }

    private companion object { const val VERIFY_TIMEOUT_MS = 90_000L }
}
