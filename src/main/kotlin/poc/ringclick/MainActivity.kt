package poc.ringclick

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
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
        if (!busy && s.kind != renderedKind) renderActions(s.kind)
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

    private fun renderActions(kind: RingKind) {
        renderedKind = kind
        actions.removeAllViews()
        when (kind) {
            RingKind.CFW -> addAction("Enter failsafe") { runner.enterFailsafe() }
            RingKind.FAILSAFE -> {
                addAction("Flash CFW") { runner.flashCfw() }
                addAction("Restore official", primary = false) { runner.restoreOfficial() }
            }
            RingKind.OFFICIAL -> addAction("Flash CFW") { runner.flashCfw() }
            RingKind.NONE -> {}
        }
    }

    private fun addAction(label: String, primary: Boolean = true, op: suspend () -> Boolean) {
        val btn = if (primary) MaterialButton(this)
        else MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
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
            renderActions(scanner.state.value.kind)
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
        val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
