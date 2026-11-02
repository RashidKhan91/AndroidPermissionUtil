package dev.universal.permissions.platform

import android.Manifest
import android.os.Build
import dev.universal.permissions.model.PermissionType

/** All API-level permission changes live here. Null means the capability has no permission on this OS. */
object PermissionCompatibility {
    fun runtimePermissions(type: PermissionType, api: Int = Build.VERSION.SDK_INT): List<String>? = when (type) {
        PermissionType.CAMERA -> listOf(Manifest.permission.CAMERA)
        PermissionType.RECORD_AUDIO -> listOf(Manifest.permission.RECORD_AUDIO)
        PermissionType.ACCESS_COARSE_LOCATION -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        PermissionType.ACCESS_FINE_LOCATION -> if (api >= 31) listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        PermissionType.ACCESS_BACKGROUND_LOCATION -> if (api >= 29) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else null
        PermissionType.READ_CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        PermissionType.WRITE_CONTACTS -> listOf(Manifest.permission.WRITE_CONTACTS)
        PermissionType.GET_ACCOUNTS -> listOf(Manifest.permission.GET_ACCOUNTS)
        PermissionType.READ_PHONE_STATE -> listOf(Manifest.permission.READ_PHONE_STATE)
        PermissionType.CALL_PHONE -> listOf(Manifest.permission.CALL_PHONE)
        PermissionType.READ_CALL_LOG -> listOf(Manifest.permission.READ_CALL_LOG)
        PermissionType.SEND_SMS -> listOf(Manifest.permission.SEND_SMS)
        PermissionType.RECEIVE_SMS -> listOf(Manifest.permission.RECEIVE_SMS)
        PermissionType.READ_MEDIA_IMAGES -> when { api >= 34 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED); api >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES); api >= 23 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE); else -> null }
        PermissionType.READ_MEDIA_VIDEO -> when { api >= 34 -> listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED); api >= 33 -> listOf(Manifest.permission.READ_MEDIA_VIDEO); api >= 23 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE); else -> null }
        PermissionType.READ_MEDIA_AUDIO -> when { api >= 33 -> listOf(Manifest.permission.READ_MEDIA_AUDIO); api >= 23 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE); else -> null }
        PermissionType.SELECTED_PHOTOS -> if (api >= 34) listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else null
        PermissionType.READ_EXTERNAL_STORAGE -> if (api in 23..32) listOf(Manifest.permission.READ_EXTERNAL_STORAGE) else null
        PermissionType.WRITE_EXTERNAL_STORAGE -> if (api in 23..28) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else null
        PermissionType.BLUETOOTH_SCAN -> if (api >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN) else if (api >= 23) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else null
        PermissionType.BLUETOOTH_CONNECT -> if (api >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else null
        PermissionType.BLUETOOTH_ADVERTISE -> if (api >= 31) listOf(Manifest.permission.BLUETOOTH_ADVERTISE) else null
        PermissionType.NEARBY_WIFI_DEVICES -> if (api >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else if (api >= 23) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else null
        PermissionType.POST_NOTIFICATIONS -> if (api >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else null
        else -> null
    }

    fun isSpecial(type: PermissionType): Boolean = type in setOf(
        PermissionType.SYSTEM_ALERT_WINDOW, PermissionType.WRITE_SETTINGS, PermissionType.MANAGE_EXTERNAL_STORAGE,
        PermissionType.PACKAGE_USAGE_STATS, PermissionType.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        PermissionType.SCHEDULE_EXACT_ALARM, PermissionType.NOTIFICATION_LISTENER,
        PermissionType.REQUEST_INSTALL_PACKAGES, PermissionType.USE_FULL_SCREEN_INTENT,
    )
}
