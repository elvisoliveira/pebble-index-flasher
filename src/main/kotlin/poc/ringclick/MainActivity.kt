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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Pebble Index CFW Manager — one Activity, offline. Three pages (ring / recordings / log)
 * live in one layout and a bottom bar toggles their visibility. A continuous BLE scan is the
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
    private lateinit var bottomNav: BottomNavigationView

    private var busy = false
    private var renderedKind: RingKind? = null
    /* The buttons are rebuilt only on a state change, so anything that changes which
     * buttons belong on screen has to be part of that key — the audio ones appear and
     * disappear as recordings come and go. */
    private var renderedAudio = false
    private var player: MediaPlayer? = null
    private lateinit var clips: ClipStore
    private lateinit var recordings: LinearLayout
    /* Auto-fetch guards. The ring drops a clip once it has been delivered, so a success
     * takes the advertisement's count to zero and cannot re-trigger. A FAILURE leaves it
     * there, and without a pause every advertisement would start another attempt. */
    private var autoFetching = false
    private var autoRetryAfter = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
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
        bottomNav = findViewById(R.id.bottomNav)
        val pages = mapOf(
            R.id.tabRing to findViewById<View>(R.id.pageRing),
            R.id.tabRecordings to findViewById<View>(R.id.pageRecordings),
            R.id.tabLog to logScroll,
        )
        bottomNav.setOnItemSelectedListener { item ->
            pages.forEach { (id, v) -> v.visibility = if (id == item.itemId) View.VISIBLE else View.GONE }
            if (item.itemId == R.id.tabRecordings) bottomNav.removeBadge(R.id.tabRecordings)
            true
        }
        clips = ClipStore(this)
        renderRecordings()

        // targetSdk 36 forces edge-to-edge: keep the content off the navigation bar.
        // The top inset is the AppBarLayout's (fitsSystemWindows in the layout).
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.systemBars()).bottom)
            insets
        }

        val images = ImageStore(this)
        findViewById<TextView>(R.id.cfwPill).text = images.cfwVersion
        logLine("App ${packageManager.getPackageInfo(packageName, 0).versionName}  ·  CFW ${images.cfwVersion}")
        scanner = RingScanner(this) { logLine(it) }
        runner = OperationRunner(this, scanner, images) { logLine(it) }

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
            RingKind.CFW -> setState("CFW", R.color.state_cfw)
            RingKind.FAILSAFE -> setState("FAILSAFE", R.color.state_failsafe)
            RingKind.OFFICIAL -> setState("OFFICIAL", R.color.state_official)
            RingKind.NONE -> setState("NOT FOUND", R.color.state_none)
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
                if (bottomNav.selectedItemId != R.id.tabRecordings) bottomNav.getOrCreateBadge(R.id.tabRecordings)
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
        if (files.isEmpty()) {
            recordings.addView(TextView(this).apply {
                text = "No recordings yet. Hold the ring's button to record."
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setPadding(8, 8, 8, 8)
            })
            return
        }
        for (file in files) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.text = ClipStore.label(file)
            btn.setOnClickListener { play(file) }
            row.addView(btn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val trash = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle)
            trash.setIconResource(R.drawable.ic_delete)
            trash.contentDescription = "Delete"
            trash.setOnClickListener { clips.delete(file); renderRecordings() }
            row.addView(trash)
            recordings.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val clear = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle)
        clear.text = "Clear all"
        clear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete all ${files.size} recordings? The ring keeps no copy.")
                .setPositiveButton("Delete") { _, _ -> clips.clear(); renderRecordings() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        recordings.addView(clear, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 24 })
    }

    /** Seconds of audio the ring is holding, or null when there is none. */
    private fun clipSeconds(s: RingState): Double? {
        val n = s.clipSamples ?: return null
        return if (n > 0) n.toDouble() / ClipDownload.SAMPLE_RATE else null
    }

    private fun setState(label: String, color: Int) {
        stateChip.text = label
        stateChip.backgroundTintList = ColorStateList.valueOf(getColor(color))
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
        Toast.makeText(this, "Playing ${ClipStore.label(file)}", Toast.LENGTH_SHORT).show()
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
        setState("NO PERMISSION", R.color.state_none)
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

        val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
