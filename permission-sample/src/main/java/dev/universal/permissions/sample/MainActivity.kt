package dev.universal.permissions.sample

import android.os.Bundle
import android.os.Build
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.universal.permissions.PermissionManager
import dev.universal.permissions.model.PermissionType

class MainActivity : ComponentActivity() {
    private lateinit var permissions: PermissionManager
    private lateinit var log: TextView
    private val catalog = listOf(
        PermissionType.CAMERA, PermissionType.RECORD_AUDIO, PermissionType.READ_MEDIA_IMAGES,
        PermissionType.READ_MEDIA_VIDEO, PermissionType.READ_MEDIA_AUDIO, PermissionType.SELECTED_PHOTOS,
        PermissionType.ACCESS_COARSE_LOCATION, PermissionType.ACCESS_FINE_LOCATION, PermissionType.ACCESS_BACKGROUND_LOCATION,
        PermissionType.BLUETOOTH_SCAN, PermissionType.BLUETOOTH_CONNECT, PermissionType.BLUETOOTH_ADVERTISE,
        PermissionType.NEARBY_WIFI_DEVICES, PermissionType.POST_NOTIFICATIONS,
        PermissionType.READ_CONTACTS, PermissionType.WRITE_CONTACTS, PermissionType.GET_ACCOUNTS,
        PermissionType.READ_PHONE_STATE, PermissionType.CALL_PHONE, PermissionType.READ_CALL_LOG,
        PermissionType.SEND_SMS, PermissionType.RECEIVE_SMS, PermissionType.READ_EXTERNAL_STORAGE,
        PermissionType.WRITE_EXTERNAL_STORAGE, PermissionType.SYSTEM_ALERT_WINDOW, PermissionType.WRITE_SETTINGS,
        PermissionType.MANAGE_EXTERNAL_STORAGE, PermissionType.PACKAGE_USAGE_STATS,
        PermissionType.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, PermissionType.SCHEDULE_EXACT_ALARM,
        PermissionType.NOTIFICATION_LISTENER, PermissionType.REQUEST_INSTALL_PACKAGES, PermissionType.USE_FULL_SCREEN_INTENT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions = PermissionManager(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply {
            text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n${Build.MANUFACTURER} ${Build.MODEL}"
            textSize = 18f
        })
        log = TextView(this).apply { text = "Choose a permission to check or request." }
        root.addView(Button(this).apply {
            text = "Request camera + microphone"
            setOnClickListener { permissions.requestMultiple(listOf(PermissionType.CAMERA, PermissionType.RECORD_AUDIO)) { batch ->
                log.text = batch.results.joinToString("\n") { "${it.permission}: ${it.status}" }
            } }
        })
        root.addView(Button(this).apply { text = "Open app settings"; setOnClickListener { permissions.openAppSettings() } })
        root.addView(log)
        catalog.forEach { type -> root.addView(Button(this).apply {
            text = "${type.name}: ${permissions.check(type).status} — tap to request"
            setOnClickListener { permissions.request(type) { result ->
                log.text = "${result.permission}: ${result.status}\n${result.message.orEmpty()}"
                text = "${type.name}: ${result.status} — tap to request"
            } }
        }) }
        setContentView(android.widget.ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) })
    }
}
