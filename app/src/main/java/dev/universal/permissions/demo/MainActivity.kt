package dev.universal.permissions.demo

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import dev.universal.permissions.PermissionManager
import dev.universal.permissions.model.PermissionBatchResult
import dev.universal.permissions.model.PermissionResult
import dev.universal.permissions.model.PermissionStatus
import dev.universal.permissions.model.PermissionType

class MainActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    private lateinit var resultView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register Activity Result launchers before this Activity reaches STARTED.
        permissionManager = PermissionManager(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        content.addView(TextView(this).apply {
            text = "Permission Library Demo"
            textSize = 24f
        })
        content.addView(TextView(this).apply {
            text = "Tap a button to show Android's permission prompt. Your choice is reported below."
            textSize = 16f
            setPadding(0, 12, 0, 16)
        })
        addRequestButton(content, "Request camera", PermissionType.CAMERA)
        addRequestButton(content, "Request microphone", PermissionType.RECORD_AUDIO)
        content.addView(Button(this).apply {
            text = "Request camera + microphone"
            setOnClickListener {
                permissionManager.requestMultiple(
                    listOf(PermissionType.CAMERA, PermissionType.RECORD_AUDIO),
                ) { batch -> showBatch(batch) }
            }
        })
        content.addView(Button(this).apply {
            text = "Check current camera status"
            setOnClickListener { showResult(permissionManager.check(PermissionType.CAMERA)) }
        })
        resultView = TextView(this).apply {
            text = "Waiting for a permission request."
            textSize = 16f
            setPadding(0, 20, 0, 0)
        }
        content.addView(resultView)

        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(-1, -2))
        })
    }

    private fun addRequestButton(parent: LinearLayout, label: String, type: PermissionType) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { permissionManager.request(type, ::showResult) }
        })
    }

    private fun showBatch(batch: PermissionBatchResult) {
        resultView.text = batch.results.joinToString("\n") { format(it) }
        batch.results.firstOrNull { it.status == PermissionStatus.PERMANENTLY_DENIED }
            ?.let(::offerSettings)
    }

    private fun showResult(result: PermissionResult) {
        resultView.text = format(result)
        if (result.status == PermissionStatus.PERMANENTLY_DENIED) offerSettings(result)
        if (result.status == PermissionStatus.GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("Permission granted")
                .setMessage("${result.permission} is ready to use.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun offerSettings(result: PermissionResult) {
        AlertDialog.Builder(this)
            .setTitle("Permission denied")
            .setMessage(result.message ?: "Enable this permission in app settings to continue.")
            .setNegativeButton("Later", null)
            .setPositiveButton("Open settings") { _, _ -> permissionManager.openAppSettings() }
            .show()
    }

    private fun format(result: PermissionResult): String = buildString {
        append("${result.permission}: ${result.status}")
        result.message?.let { append("\n$it") }
    }
}
