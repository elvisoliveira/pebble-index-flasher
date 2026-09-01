package poc.ringclick

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Pebble Index CFW Manager — single-screen, offline. A continuous BLE scan is the
 * source of truth; the status card and buttons react to the ring's state (OFFICIAL /
 * FAILSAFE / CFW / not found). Each action is a guided chain (OperationRunner) that
 * hides the discovery/verification gotchas.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var scanner: RingScanner
    private lateinit var runner: OperationRunner

    private lateinit var stateChip: TextView
    private lateinit var detailView: TextView
    private lateinit var instructionView: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var actions: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var busy = false
    private var renderedKind: RingKind? = null
    /* The buttons are rebuilt only on a state change, so anything that changes which
     * buttons belong on screen has to be part of that key — the audio ones appear and
     * disappear as recordings come and go. */
    private var renderedAudio = false
    private var player: MediaPlayer? = null
    private lateinit var clips: ClipStore
    private lateinit var recordings: LinearLayout
    private lateinit var recordingsTitle: TextView
    /* Auto-fetch guards. The ring drops a clip once it has been delivered, so a success
     * takes the advertisement's count to zero and cannot re-trigger. A FAILURE leaves it
     * there, and without a pause every advertisement would start another attempt. */
    private var autoFetching = false
    private var autoRetryAfter = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)   // Material You wallpaper palette
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateChip = findViewById(R.id.stateChip)
        detailView = findViewById(R.id.detailView)
        instructionView = findViewById(R.id.instructionView)
        progress = findViewById(R.id.progress)
        actions = findViewById(R.id.actions)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        recordings = findViewById(R.id.recordings)
        recordingsTitle = findViewById(R.id.recordingsTitle)
        clips = ClipStore(this)
        renderRecordings()

        // targetSdk 36 forces edge-to-edge: keep the content off the system bars.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        scanner = RingScanner(this) { logLine(it) }
        runner = OperationRunner(this, scanner, ImageStore(this)) { logLine(it) }

        if (PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            start()
        } else {
            requestPermissions(PERMISSIONS, 0)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            start()
        } else {
            renderPermissionDenied()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stop()
        player?.release()
        player = null
        scope.cancel()
    }

    private fun start() {
        scanner.start(scope)
        scope.launch {
            scanner.state.collect { render(it) }
        }
    }

    private fun render(s: RingState) {
        when (s.kind) {
            RingKind.CFW -> setState("CFW", 0xFF2E7D32)
            RingKind.FAILSAFE -> setState("FAILSAFE", 0xFFEF6C00)
            RingKind.OFFICIAL -> setState("OFFICIAL", 0xFF1565C0)
            RingKind.NONE -> setState("NOT FOUND", 0xFF616161)
        }
        detailView.text = if (s.kind == RingKind.NONE) "No ring in range." else buildString {
            append(s.name).append("  ·  ").append(s.address).append('\n')
            append("Signal: ").append(signalWord(s.rssi)).append(" (").append(s.rssi).append(" dBm)")
            if (s.kind == RingKind.CFW) {
                append("\nClicks: ").append(s.totalClicks)
                if (s.counter != null) append("  (counter ").append(s.counter).append(')')
                val secs = clipSeconds(s)
                if (secs != null) append("\nRecording waiting: %.1f s".format(secs))
            }
            if (s.kind == RingKind.FAILSAFE) {
                append("\n\nTo restore the official firmware, keep the ring in failsafe and open the official app — it will sync and reinstall it.")
            }
        }
        instruction(when {
            busy -> "Working — keep the ring close and the screen on."
            s.kind == RingKind.NONE -> "Bring the ring closer and press its button."
            s.rssi < -80 -> "Weak signal — bring the ring closer."
            else -> null
        })
        // Rebuild buttons only on a state change — render() fires per advertisement,
        // and recreating a Button mid-tap would eat the tap.
        val hasAudio = clipSeconds(s) != null
        if (!busy && (s.kind != renderedKind || hasAudio != renderedAudio)) renderActions(s.kind, hasAudio)
        if (hasAudio) maybeAutoFetch(s)
    }

    /*
     * A recording announces itself: the ring advertises after every click and after every
     * recording, and the count it carries is non-zero only while a clip is still waiting.
     * So there is nothing to poll and nothing to ask — seeing one is reason enough to go
     * and get it, and the ring clearing it on delivery is what stops that from repeating.
     */
    private fun maybeAutoFetch(s: RingState) {
        if (busy || autoFetching || SystemClock.elapsedRealtime() < autoRetryAfter) return
        val address = s.address ?: return
        autoFetching = true
        scope.launch {
            logLine("\n=== Recording waiting (%.1f s) — fetching ===".format(clipSeconds(s) ?: 0.0))
            val wav = try {
                ClipDownload.fetch(this@MainActivity, address) { logLine(it) }
            } catch (e: Exception) {
                logLine("EXCEPTION: $e"); null
            }
            if (wav != null) {
                val file = clips.save(wav)
                logLine("=== Saved ${file.name} ===\n")
                renderRecordings()
            } else {
                /* Back off rather than retry on the next advertisement: the clip is still
                 * on the ring, so the trigger would fire again immediately. */
                autoRetryAfter = SystemClock.elapsedRealtime() + AUTO_RETRY_PAUSE_MS
                logLine("=== Fetch failed — will try again shortly ===\n")
            }
            autoFetching = false
        }
    }

    private fun renderRecordings() {
        val files = clips.list()
        recordings.removeAllViews()
        recordingsTitle.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        for (file in files.take(MAX_LISTED)) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.text = ClipStore.label(file)
            btn.setOnClickListener { play(file) }
            recordings.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    /** Seconds of audio the ring is holding, or null when there is none. */
    private fun clipSeconds(s: RingState): Double? {
        val n = s.clipSamples ?: return null
        return if (n > 0) n.toDouble() / ClipDownload.SAMPLE_RATE else null
    }

    private fun setState(label: String, color: Long) {
        stateChip.text = label
        stateChip.backgroundTintList = ColorStateList.valueOf(color.toInt())
    }

    private fun instruction(text: String?) {
        instructionView.text = text ?: ""
        instructionView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun signalWord(rssi: Int) = when {
        rssi >= -60 -> "strong"
        rssi >= -75 -> "good"
        else -> "weak"
    }

    private fun renderActions(kind: RingKind, hasAudio: Boolean = false) {
        renderedKind = kind
        renderedAudio = hasAudio
        actions.removeAllViews()
        when (kind) {
            RingKind.CFW -> {
                /* Recordings arrive on their own; this only exists for the case where the
                 * automatic attempt failed and the user would rather not wait out the
                 * pause. It sits above "Enter failsafe" because a reset takes the clip
                 * with it — the ring holds it in RAM. */
                if (hasAudio) addInstantAction("Fetch recording now") {
                    autoRetryAfter = 0L
                    maybeAutoFetch(scanner.state.value)
                }
                addAction("Enter failsafe") { runner.enterFailsafe() }
            }
            RingKind.FAILSAFE -> addAction("Flash CFW") { runner.flashCfw() }
            RingKind.OFFICIAL -> addAction("Flash CFW") { runner.flashCfw() }
            RingKind.NONE -> {}
        }
    }

    private fun play(file: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { logLine("Playback finished.") }
            prepare()
            start()
        }
        logLine("Playing ${ClipStore.label(file)}…")
    }

    /* Playing is instant and local; routing it through runOp would blank the buttons and
     * spin a progress bar for something that has already happened. */
    private fun addInstantAction(label: String, action: () -> Unit) {
        val btn = MaterialButton(this)
        btn.text = label
        btn.setOnClickListener { action() }
        actions.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun addAction(label: String, op: suspend () -> Boolean) {
        val btn = MaterialButton(this)
        btn.text = label
        btn.setOnClickListener { runOp(label, op) }
        actions.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun runOp(label: String, op: suspend () -> Boolean) {
        if (busy) return
        busy = true
        progress.visibility = View.VISIBLE
        actions.removeAllViews()
        instruction("Working — keep the ring close and the screen on.")
        logLine("\n=== $label ===")
        scope.launch {
            val ok = try { op() } catch (e: Exception) { logLine("EXCEPTION: $e"); false }
            logLine(if (ok) "=== $label: OK ===\n" else "=== $label: failed ===\n")
            busy = false
            progress.visibility = View.GONE
            renderActions(scanner.state.value.kind, clipSeconds(scanner.state.value) != null)
        }
    }

    private fun renderPermissionDenied() {
        setState("NO PERMISSION", 0xFF616161)
        detailView.text = "Bluetooth permission is required to find the ring."
        actions.removeAllViews()
        val retry = MaterialButton(this).apply {
            text = "Grant permission"
            setOnClickListener {
                if (PERMISSIONS.any { shouldShowRequestPermissionRationale(it) }) {
                    requestPermissions(PERMISSIONS, 0)
                } else {   // "don't ask again": only the app settings screen can grant it now
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)))
                }
            }
        }
        actions.addView(retry, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun logLine(line: String) {
        runOnUiThread {
            // Only auto-scroll if the user is already at the bottom — don't yank
            // them back down while they re-read an earlier line.
            val atBottom = !logScroll.canScrollVertically(1)
            logView.append("$line\n")
            val text = logView.text
            if (text.length > 40_000) logView.text = text.subSequence(text.length - 30_000, text.length)
            if (atBottom) logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private companion object {
        const val AUTO_RETRY_PAUSE_MS = 20_000L
        const val MAX_LISTED = 8

        val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
