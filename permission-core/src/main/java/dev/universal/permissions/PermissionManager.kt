package dev.universal.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.universal.permissions.model.*
import dev.universal.permissions.platform.PermissionCompatibility

/** Create in onCreate, before the owner reaches STARTED. Keep one manager per Activity/Fragment. */
class PermissionManager private constructor(
    private val context: Context,
    private val caller: ActivityResultCaller,
    private val rationale: (String) -> Boolean,
) {
    private val requests = ArrayDeque<Pair<PermissionType, (PermissionResult) -> Unit>>()
    private var active: Pair<PermissionType, (PermissionResult) -> Unit>? = null
    private var activeRuntimeNames = emptyList<String>()
    private var runtimeLauncher: ActivityResultLauncher<Array<String>>
    private var settingsLauncher: ActivityResultLauncher<Intent>

    constructor(activity: ComponentActivity) : this(activity, activity) { }
    private constructor(activity: ComponentActivity, caller: ActivityResultCaller) : this(
        activity.applicationContext, caller, activity::shouldShowRequestPermissionRationale,
    )
    constructor(fragment: Fragment) : this(fragment.requireContext().applicationContext, fragment, fragment::shouldShowRequestPermissionRationale)

    init {
        runtimeLauncher = caller.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val (type, callback) = active ?: return@registerForActivityResult
            val granted = activeRuntimeNames.all { grants[it] == true || isGranted(it) }
            val missing = activeRuntimeNames.filterNot(::isGranted)
            val partial = hasPartialGrant(type)
            val status = when {
                granted -> PermissionStatus.GRANTED
                partial -> PermissionStatus.PARTIALLY_GRANTED
                missing.any { !rationale(it) && wasRequested(it) } -> PermissionStatus.PERMANENTLY_DENIED
                else -> PermissionStatus.DENIED
            }
            missing.forEach { markRequested(it) }
            finish(result(type, status, granted, status == PermissionStatus.PERMANENTLY_DENIED,
                status == PermissionStatus.DENIED, messageFor(status)))
        }
        settingsLauncher = caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _: ActivityResult ->
            active?.first?.let { type ->
                val granted = isSpecialGranted(type)
                finish(result(type, if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
                    granted, false, false, if (granted) null else "Access was not enabled in Settings."))
            }
        }
    }

    /** Request one capability. Must be created before the Activity/Fragment is STARTED. */
    fun request(permission: PermissionType, callback: (PermissionResult) -> Unit) = enqueue(permission, callback)

    /** Requests in order, returning one result for every capability. */
    fun requestMultiple(permissions: List<PermissionType>, callback: (PermissionBatchResult) -> Unit) {
        if (permissions.isEmpty()) { callback(PermissionBatchResult(emptyList())); return }
        val results = mutableListOf<PermissionResult>()
        permissions.distinct().forEach { type -> enqueue(type) { result ->
            results += result
            if (results.size == permissions.distinct().size) callback(PermissionBatchResult(results))
        } }
    }

    /** Read current state without showing UI. */
    fun check(permission: PermissionType): PermissionResult {
        if (PermissionCompatibility.isSpecial(permission)) {
            val granted = isSpecialGranted(permission)
            return result(permission, if (granted) PermissionStatus.GRANTED else PermissionStatus.REQUIRES_SETTINGS,
                granted, false, false, if (granted) null else "This access is controlled in Android Settings.")
        }
        val names = PermissionCompatibility.runtimePermissions(permission)
            ?: return result(permission, PermissionStatus.NOT_AVAILABLE, false, false, false, "This permission is not available on this Android version.")
        val missing = names.filterNot(::isGranted)
        return when {
            missing.isEmpty() -> result(permission, PermissionStatus.GRANTED, true, false, false, null)
            hasPartialGrant(permission) -> result(permission, PermissionStatus.PARTIALLY_GRANTED, false, false, false, messageFor(PermissionStatus.PARTIALLY_GRANTED))
            else -> result(permission, PermissionStatus.DENIED, false, false, true, "Permission has not been granted.")
        }
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun enqueue(type: PermissionType, callback: (PermissionResult) -> Unit) {
        requests.addLast(type to callback)
        pump()
    }

    private fun pump() {
        if (active != null || requests.isEmpty()) return
        val next = requests.removeFirst()
        active = next
        val (type, _) = next
        if (PermissionCompatibility.isSpecial(type)) {
            if (isSpecialGranted(type)) { finish(result(type, PermissionStatus.GRANTED, true, false, false, null)); return }
            val requiredDeclaration = specialManifestPermission(type)
            if (requiredDeclaration != null && !isDeclared(requiredDeclaration)) {
                finish(result(type, PermissionStatus.ERROR, false, false, false,
                    "Declare $requiredDeclaration in the host app manifest.")); return
            }
            val intent = specialIntent(type)
            if (intent == null) { finish(result(type, PermissionStatus.NOT_AVAILABLE, false, false, false, "No Settings screen is available for this access.")); return }
            try { settingsLauncher.launch(intent) }
            catch (e: Exception) { finish(result(type, PermissionStatus.ERROR, false, false, false, e.message ?: "Could not open Settings.")) }
            return
        }
        val names = PermissionCompatibility.runtimePermissions(type)
        if (names == null) { finish(result(type, PermissionStatus.NOT_AVAILABLE, false, false, false, "This permission is not available on this Android version.")); return }
        val undeclared = names.filterNot(::isDeclared)
        if (undeclared.isNotEmpty()) {
            finish(result(type, PermissionStatus.ERROR, false, false, false, "Declare ${undeclared.joinToString()} in the host app manifest.")); return
        }
        val missing = names.filterNot(::isGranted)
        if (missing.isEmpty()) { finish(result(type, PermissionStatus.GRANTED, true, false, false, null)); return }
        activeRuntimeNames = missing
        try { runtimeLauncher.launch(missing.toTypedArray()) }
        catch (e: Exception) { finish(result(type, PermissionStatus.ERROR, false, false, false, e.message ?: "Could not request permission.")) }
    }

    private fun finish(result: PermissionResult) {
        val callback = active?.second
        active = null; activeRuntimeNames = emptyList()
        callback?.invoke(result)
        pump()
    }

    private fun result(type: PermissionType, status: PermissionStatus, granted: Boolean, permanent: Boolean,
                       canRequestAgain: Boolean, message: String?) = PermissionResult(
        permission = type, status = status, isGranted = granted, isPermanentlyDenied = permanent,
        canRequestAgain = canRequestAgain, requiresSettings = status == PermissionStatus.REQUIRES_SETTINGS || permanent,
        androidVersion = Build.VERSION.SDK_INT, message = message,
    )

    private fun isGranted(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    private fun isDeclared(name: String): Boolean = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(name) == true
    } catch (_: Exception) { false }
    private fun wasRequested(name: String) = prefs().getBoolean("requested:$name", false)
    private fun markRequested(name: String) { prefs().edit().putBoolean("requested:$name", true).apply() }
    private fun prefs() = context.getSharedPreferences("universal-permissions", Context.MODE_PRIVATE)
    private fun messageFor(status: PermissionStatus) = when (status) {
        PermissionStatus.PERMANENTLY_DENIED -> "Permission appears permanently denied. Open app settings to enable it."
        PermissionStatus.RESTRICTED -> "Permission is blocked by device policy. Contact your administrator."
        PermissionStatus.PARTIALLY_GRANTED -> "Only approximate location or selected photos/videos are available."
        PermissionStatus.DENIED -> "Permission was denied. You can request it again."
        else -> null
    }

    private fun hasPartialGrant(type: PermissionType): Boolean = when (type) {
        PermissionType.ACCESS_FINE_LOCATION -> Build.VERSION.SDK_INT >= 31 &&
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) && !isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        PermissionType.READ_MEDIA_IMAGES, PermissionType.READ_MEDIA_VIDEO -> Build.VERSION.SDK_INT >= 34 &&
            isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
            !isGranted(if (type == PermissionType.READ_MEDIA_IMAGES) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_MEDIA_VIDEO)
        else -> false
    }

    private fun specialManifestPermission(type: PermissionType): String? = when (type) {
        PermissionType.SYSTEM_ALERT_WINDOW -> Manifest.permission.SYSTEM_ALERT_WINDOW
        PermissionType.WRITE_SETTINGS -> Manifest.permission.WRITE_SETTINGS
        PermissionType.MANAGE_EXTERNAL_STORAGE -> Manifest.permission.MANAGE_EXTERNAL_STORAGE
        PermissionType.PACKAGE_USAGE_STATS -> Manifest.permission.PACKAGE_USAGE_STATS
        PermissionType.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        PermissionType.SCHEDULE_EXACT_ALARM -> Manifest.permission.SCHEDULE_EXACT_ALARM
        PermissionType.REQUEST_INSTALL_PACKAGES -> Manifest.permission.REQUEST_INSTALL_PACKAGES
        PermissionType.USE_FULL_SCREEN_INTENT -> Manifest.permission.USE_FULL_SCREEN_INTENT
        PermissionType.NOTIFICATION_LISTENER -> null // Requires a service protected by BIND_NOTIFICATION_LISTENER_SERVICE.
        else -> null
    }

    private fun isSpecialGranted(type: PermissionType): Boolean = when (type) {
        PermissionType.SYSTEM_ALERT_WINDOW -> Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        PermissionType.WRITE_SETTINGS -> Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(context)
        PermissionType.MANAGE_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT < 30 || EnvironmentCompat.isExternalStorageManager()
        PermissionType.PACKAGE_USAGE_STATS -> hasAppOp("android:get_usage_stats")
        PermissionType.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> Build.VERSION.SDK_INT < 23 || (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName)
        PermissionType.SCHEDULE_EXACT_ALARM -> Build.VERSION.SDK_INT < 31 || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
        PermissionType.NOTIFICATION_LISTENER -> Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true
        PermissionType.REQUEST_INSTALL_PACKAGES -> Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
        PermissionType.USE_FULL_SCREEN_INTENT -> Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
        else -> false
    }

    private fun hasAppOp(op: String): Boolean = try {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        manager.checkOpNoThrow(op, android.os.Process.myUid(), context.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun specialIntent(type: PermissionType): Intent? {
        val pkgUri = Uri.parse("package:${context.packageName}")
        return (when (type) {
            PermissionType.SYSTEM_ALERT_WINDOW -> if (Build.VERSION.SDK_INT >= 23) Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri) else null
            PermissionType.WRITE_SETTINGS -> if (Build.VERSION.SDK_INT >= 23) Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri) else null
            PermissionType.MANAGE_EXTERNAL_STORAGE -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri) else null
            PermissionType.PACKAGE_USAGE_STATS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            PermissionType.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> if (Build.VERSION.SDK_INT >= 23) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri) else null
            PermissionType.SCHEDULE_EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri) else null
            PermissionType.NOTIFICATION_LISTENER -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            PermissionType.REQUEST_INSTALL_PACKAGES -> if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri) else null
            PermissionType.USE_FULL_SCREEN_INTENT -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri) else null
            else -> null
        }).let { intent -> intent?.takeIf { it.resolveActivity(context.packageManager) != null } }
    }
}

private object EnvironmentCompat {
    fun isExternalStorageManager() = Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
}
