package dev.universal.permissions.sample

import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.universal.permissions.PermissionManager
import dev.universal.permissions.model.PermissionBatchResult
import dev.universal.permissions.model.PermissionResult
import dev.universal.permissions.model.PermissionStatus
import dev.universal.permissions.model.PermissionType

class MainActivity : ComponentActivity() {
    private lateinit var permissions: PermissionManager
    private lateinit var log: TextView
    private val permissionButtons = linkedMapOf<PermissionType, Button>()

    private val catalog = listOf(
        PermissionType.CAMERA,
        PermissionType.RECORD_AUDIO,
        PermissionType.READ_MEDIA_IMAGES,
        PermissionType.READ_MEDIA_VIDEO,
        PermissionType.READ_MEDIA_AUDIO,
        PermissionType.SELECTED_PHOTOS,
        PermissionType.ACCESS_COARSE_LOCATION,
        PermissionType.ACCESS_FINE_LOCATION,
        PermissionType.ACCESS_BACKGROUND_LOCATION,
        PermissionType.BLUETOOTH_SCAN,
        PermissionType.BLUETOOTH_CONNECT,
        PermissionType.BLUETOOTH_ADVERTISE,
        PermissionType.NEARBY_WIFI_DEVICES,
        PermissionType.POST_NOTIFICATIONS,
        PermissionType.READ_CONTACTS,
        PermissionType.WRITE_CONTACTS,
        PermissionType.GET_ACCOUNTS,
        PermissionType.READ_PHONE_STATE,
        PermissionType.CALL_PHONE,
        PermissionType.READ_CALL_LOG,
        PermissionType.SEND_SMS,
        PermissionType.RECEIVE_SMS,
        PermissionType.READ_EXTERNAL_STORAGE,
        PermissionType.WRITE_EXTERNAL_STORAGE,
        PermissionType.SYSTEM_ALERT_WINDOW,
        PermissionType.WRITE_SETTINGS,
        PermissionType.MANAGE_EXTERNAL_STORAGE,
        PermissionType.PACKAGE_USAGE_STATS,
        PermissionType.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        PermissionType.SCHEDULE_EXACT_ALARM,
        PermissionType.NOTIFICATION_LISTENER,
        PermissionType.REQUEST_INSTALL_PACKAGES,
        PermissionType.USE_FULL_SCREEN_INTENT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register Activity Result launchers before the Activity reaches STARTED.
        permissions = PermissionManager(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        content.addView(TextView(this).apply {
            text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "${Build.MANUFACTURER} ${Build.MODEL}"
            textSize = 18f
        })

        log = TextView(this).apply {
            text = "Choose a permission to check or request."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }

        content.addView(Button(this).apply {
            text = "Request camera + microphone"
            isAllCaps = false
            setOnClickListener {
                log.text = "Checking device owner/root access…"
                permissions.requestMultiple(
                    listOf(PermissionType.CAMERA, PermissionType.RECORD_AUDIO),
                ) { batch ->
                    showBatchResult(batch)
                }
            }
        })

        content.addView(Button(this).apply {
            text = "Open app settings"
            isAllCaps = false
            setOnClickListener {
                log.text = "Opening this app's settings. Permission states refresh when you return."
                permissions.openAppSettings()
            }
        })

        content.addView(log)

        catalog.forEach { type ->
            val permissionButton = Button(this).apply {
                isAllCaps = false
                setOnClickListener {
                    log.text = "Checking device owner/root access for ${type.name}…"
                    permissions.request(type) { result ->
                        showPermissionResult(result)
                    }
                }
            }
            permissionButtons[type] = permissionButton
            content.addView(permissionButton)
        }

        setContentView(ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })

        refreshPermissionButtons()
    }

    override fun onResume() {
        super.onResume()
        if (::permissions.isInitialized) {
            refreshPermissionButtons()
        }
    }

    private fun refreshPermissionButtons() {
        permissionButtons.forEach { (type, button) ->
            val current = permissions.check(type)
            button.text = "${type.name}: ${current.status} — tap to request"
        }
    }

    private fun showPermissionResult(result: PermissionResult) {
        val outcome = when (result.status) {
            PermissionStatus.GRANTED -> "Granted"
            PermissionStatus.PARTIALLY_GRANTED -> "Partially granted"
            PermissionStatus.DENIED -> "Not granted; you can request again"
            PermissionStatus.PERMANENTLY_DENIED -> "Denied; open app settings to enable it"
            PermissionStatus.REQUIRES_SETTINGS -> "Needs approval in Android Settings"
            PermissionStatus.NOT_AVAILABLE -> "Not available on this Android version"
            PermissionStatus.RESTRICTED -> "Blocked by device policy"
            PermissionStatus.ERROR -> "Could not complete the request"
        }

        log.text = buildString {
            append("${result.permission}: $outcome")
            result.message?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
        }
        refreshPermissionButtons()
    }

    private fun showBatchResult(batch: PermissionBatchResult) {
        log.text = batch.results.joinToString("\n\n") { result ->
            val outcome = when (result.status) {
                PermissionStatus.GRANTED -> "Granted"
                PermissionStatus.PARTIALLY_GRANTED -> "Partially granted"
                PermissionStatus.DENIED -> "Not granted"
                PermissionStatus.PERMANENTLY_DENIED -> "Permanently denied"
                PermissionStatus.REQUIRES_SETTINGS -> "Needs Android Settings"
                PermissionStatus.NOT_AVAILABLE -> "Not available on this Android version"
                PermissionStatus.RESTRICTED -> "Blocked by device policy"
                PermissionStatus.ERROR -> "Request error"
            }

            "${result.permission}: $outcome" +
                    (result.message?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
        }
        refreshPermissionButtons()
    }
}
