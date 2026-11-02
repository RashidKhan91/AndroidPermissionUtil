package dev.universal.permissions.model

/** A user-facing capability. Compatibility mapping resolves this to platform permissions at runtime. */
enum class PermissionType {
    CAMERA, RECORD_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO, SELECTED_PHOTOS,
    READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE,
    ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION,
    READ_CONTACTS, WRITE_CONTACTS, GET_ACCOUNTS, READ_PHONE_STATE, CALL_PHONE, READ_CALL_LOG, SEND_SMS, RECEIVE_SMS,
    BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, NEARBY_WIFI_DEVICES,
    POST_NOTIFICATIONS,
    SYSTEM_ALERT_WINDOW, WRITE_SETTINGS, MANAGE_EXTERNAL_STORAGE, PACKAGE_USAGE_STATS,
    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, SCHEDULE_EXACT_ALARM, NOTIFICATION_LISTENER,
    REQUEST_INSTALL_PACKAGES, USE_FULL_SCREEN_INTENT,
}

enum class PermissionStatus {
    GRANTED, DENIED, PERMANENTLY_DENIED, PARTIALLY_GRANTED, RESTRICTED,
    NOT_AVAILABLE, REQUIRES_SETTINGS, ERROR
}

data class PermissionResult(
    val permission: PermissionType,
    val status: PermissionStatus,
    val isGranted: Boolean,
    val isPermanentlyDenied: Boolean,
    val canRequestAgain: Boolean,
    val requiresSettings: Boolean,
    val androidVersion: Int,
    val message: String? = null,
)

data class PermissionBatchResult(val results: List<PermissionResult>) {
    val allGranted: Boolean get() = results.isNotEmpty() && results.all { it.isGranted }
    val hasPartialGrant: Boolean get() = results.any { it.isGranted } && results.any { !it.isGranted }
}
