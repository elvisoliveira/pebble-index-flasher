package poc.ringclick

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var scanner: RingScanner
    private lateinit var runner: OperationRunner

    private lateinit var statusView: TextView
    private lateinit var actions: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var busy = false
    private var renderedKind: RingKind? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()   // targetSdk 36 forces edge-to-edge; the action bar would cover the card
        buildUi()
        scanner = RingScanner(this) { logLine(it) }
        runner = OperationRunner(this, scanner, ImageStore(this)) { logLine(it) }

        if (PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            start()
        } else {
            requestPermissions(PERMISSIONS, 0)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) start()
        else statusView.text = "Bluetooth permissions denied."
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
        val kindLabel = if (s.kind == RingKind.NONE) "not found" else s.kind.name
        val color = when (s.kind) {
            RingKind.CFW -> Color.parseColor("#2E7D32")
            RingKind.FAILSAFE -> Color.parseColor("#EF6C00")
            RingKind.OFFICIAL -> Color.parseColor("#1565C0")
            RingKind.NONE -> Color.GRAY
        }
        statusView.setTextColor(color)
        statusView.text = buildString {
            append("Ring: ").append(kindLabel).append('\n')
            if (s.kind == RingKind.NONE) {
                append("Bring the ring closer and press its button.")
            } else {
                append(s.name).append("  ").append(s.address).append('\n')
                append("RSSI ").append(s.rssi).append(" dBm")
                if (s.rssi < -80) append("  (bring the ring closer)")
                if (s.kind == RingKind.CFW) {
                    append("\nClicks: ").append(s.totalClicks)
                    if (s.counter != null) append("  (counter ").append(s.counter).append(')')
                }
            }
        }
        // Rebuild buttons only on a state change — render() fires per advertisement,
        // and recreating a Button mid-tap would eat the tap.
        if (!busy && s.kind != renderedKind) renderActions(s.kind)
    }

    private fun renderActions(kind: RingKind) {
        renderedKind = kind
        actions.removeAllViews()
        when (kind) {
            RingKind.CFW -> addAction("Enter failsafe") { runner.enterFailsafe() }
            RingKind.FAILSAFE -> {
                addAction("Flash CFW") { runner.flashCfw() }
                addAction("Restore official") { runner.restoreOfficial() }
            }
            RingKind.OFFICIAL -> addAction("Flash CFW") { runner.flashCfw() }
            RingKind.NONE -> {}
        }
    }

    private fun addAction(label: String, op: suspend () -> Boolean) {
        val btn = Button(this).apply {
            text = label
            setOnClickListener { runOp(label, op) }
        }
        actions.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun runOp(label: String, op: suspend () -> Boolean) {
        if (busy) return
        busy = true
        actions.removeAllViews()
        logLine("\n=== $label ===")
        scope.launch {
            val ok = try { op() } catch (e: Exception) { logLine("EXCEPTION: $e"); false }
            logLine(if (ok) "=== $label: OK ===\n" else "=== $label: failed ===\n")
            busy = false
            renderActions(scanner.state.value.kind)
        }
    }

    private fun buildUi() {
        statusView = TextView(this).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 18f
            setPadding(32, 32, 32, 24)
            text = "Starting…"
        }
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 12)
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(24, 8, 24, 24)
        }
        logScroll = ScrollView(this).apply {
            addView(logView, MATCH_PARENT, WRAP_CONTENT)
            keepScreenOn = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            // edge-to-edge: push the content below the status bar / above the nav bar.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(
                View(this@MainActivity).apply { setBackgroundColor(Color.LTGRAY) },
                LinearLayout.LayoutParams(MATCH_PARENT, 2),
            )
            addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun logLine(line: String) {
        runOnUiThread {
            logView.append("$line\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
