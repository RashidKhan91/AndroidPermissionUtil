package dev.universal.permissions

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.universal.permissions.model.*
import dev.universal.permissions.platform.PermissionCompatibility

/** One manager per owner. Create Activity managers in onCreate and Fragment managers as eager properties. */
class PermissionManager private constructor(
    private val contextProvider: () -> Context,
    private val caller: ActivityResultCaller,
    private val rationale: (String) -> Boolean,
) {
    private val context: Context by lazy(LazyThreadSafetyMode.NONE) { contextProvider().applicationContext }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requests = ArrayDeque<Pair<PermissionType, (PermissionResult) -> Unit>>()
    private var active: Pair<PermissionType, (PermissionResult) -> Unit>? = null
    private var activeRuntimeNames = emptyList<String>()
    private var runtimeLauncher: ActivityResultLauncher<Array<String>>
    private var settingsLauncher: ActivityResultLauncher<Intent>

    constructor(activity: ComponentActivity) : this(
        { activity.applicationContext }, activity, activity::shouldShowRequestPermissionRationale,
    )

    /** Construct eagerly as a Fragment property so Activity Result launchers register before CREATED. */
    constructor(fragment: Fragment) : this(
        { fragment.requireContext().applicationContext }, fragment, fragment::shouldShowRequestPermissionRationale,
    )

    init {
        runtimeLauncher = caller.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val (type, callback) = active ?: return@registerForActivityResult
            val granted = activeRuntimeNames.all { grants[it] == true || isGranted(it) }
            val missing = activeRuntimeNames.filterNot(::isGranted)
            val partial = hasPartialGrant(type)
            val status = when {
                granted -> PermissionStatus.GRANTED
                partial -> PermissionStatus.PARTIALLY_GRANTED
                missing.any { !shouldShowRationale(it) && wasRequested(it) } -> PermissionStatus.PERMANENTLY_DENIED
                else -> PermissionStatus.DENIED
            }
            missing.forEach { markRequested(it) }
            finish(result(type, status, granted, status == PermissionStatus.PERMANENTLY_DENIED,
                status == PermissionStatus.DENIED, messageFor(status)))
        }
        settingsLauncher = caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _: ActivityResult ->
            active?.first?.let { type ->
                val granted = isSpecialGrantedSafely(type)
                finish(result(type, if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
                    granted, false, false, if (granted) null else "Access was not enabled in Settings."))
            }
        }
    }

    /** Request one capability. Must be created before the Activity/Fragment is STARTED. */
    fun request(permission: PermissionType, callback: (PermissionResult) -> Unit) = onMain {
        enqueue(permission, callback)
    }

    /** Requests in order, returning one result for every capability. */
    fun requestMultiple(permissions: List<PermissionType>, callback: (PermissionBatchResult) -> Unit) {
        onMain {
            val requested = permissions.distinct()
            if (requested.isEmpty()) { callback(PermissionBatchResult(emptyList())); return@onMain }
            val results = mutableListOf<PermissionResult>()
            requested.forEach { type -> enqueue(type) { result ->
                results += result
                if (results.size == requested.size) callback(PermissionBatchResult(results.toList()))
            } }
        }
    }

    /** Read current state without showing UI. */
    fun check(permission: PermissionType): PermissionResult {
        if (PermissionCompatibility.isSpecial(permission)) {
            if (permission == PermissionType.NOTIFICATION_LISTENER && notificationListenerComponents().isEmpty()) {
                return result(permission, PermissionStatus.ERROR, false, false, false,
                    "Declare a NotificationListenerService protected by BIND_NOTIFICATION_LISTENER_SERVICE in the host app manifest.")
            }
            val granted = isSpecialGrantedSafely(permission)
            return result(permission, if (granted) PermissionStatus.GRANTED else PermissionStatus.REQUIRES_SETTINGS,
                granted, false, false, if (granted) null else "This access is controlled in Android Settings.")
        }
        val names = PermissionCompatibility.runtimePermissions(permission)
            ?: return result(permission, PermissionStatus.NOT_AVAILABLE, false, false, false, "This permission is not available on this Android version.")
        val missing = names.filterNot(::isGranted)
        return when {
            missing.isEmpty() -> result(permission, PermissionStatus.GRANTED, true, false, false, null)
            hasPartialGrant(permission) -> result(permission, PermissionStatus.PARTIALLY_GRANTED, false, false, false, messageFor(PermissionStatus.PARTIALLY_GRANTED))
            missing.any { !shouldShowRationale(it) && wasRequested(it) } -> result(
                permission, PermissionStatus.PERMANENTLY_DENIED, false, true, false,
                messageFor(PermissionStatus.PERMANENTLY_DENIED),
            )
            else -> result(permission, PermissionStatus.DENIED, false, false, true, "Permission has not been granted.")
        }
    }

    fun openAppSettings() {
        onMain {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent) } catch (_: Exception) { /* Settings may be unavailable on OEM builds. */ }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun shouldShowRationale(permission: String): Boolean = try {
        rationale(permission)
    } catch (_: Exception) {
        false
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
            if (isSpecialGrantedSafely(type)) { finish(result(type, PermissionStatus.GRANTED, true, false, false, null)); return }
            if (type == PermissionType.NOTIFICATION_LISTENER && notificationListenerComponents().isEmpty()) {
                finish(result(type, PermissionStatus.ERROR, false, false, false,
                    "Declare a NotificationListenerService protected by BIND_NOTIFICATION_LISTENER_SERVICE in the host app manifest."))
                return
            }
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
        attemptPrivilegedGrant(missing) { stillMissing ->
            if (active == null) return@attemptPrivilegedGrant
            if (stillMissing.isEmpty()) {
                finish(result(type, PermissionStatus.GRANTED, true, false, false, null))
                return@attemptPrivilegedGrant
            }
            activeRuntimeNames = stillMissing
            try { runtimeLauncher.launch(stillMissing.toTypedArray()) }
            catch (e: Exception) { finish(result(type, PermissionStatus.ERROR, false, false, false, e.message ?: "Could not request permission.")) }
        }
    }

    /**
     * Device/profile owners can use Android's supported policy API. On rooted devices,
     * try `su -c pm grant`; if neither path grants everything, the normal system prompt runs.
     */
    private fun attemptPrivilegedGrant(names: List<String>, callback: (List<String>) -> Unit) {
        grantAsDeviceOrProfileOwner(names)
        val afterOwnerGrant = names.filterNot(::isGranted)
        if (afterOwnerGrant.isEmpty()) {
            callback(emptyList())
            return
        }

        Thread({
            val rootCheck = runSuCommand("id -u")
            if (rootCheck?.first == 0 && rootCheck.second.trim() == "0") {
                afterOwnerGrant.filterNot(::isGranted).forEach { permission ->
                    // Both values come from the host package identity and the library's fixed permission map.
                    runSuCommand("pm grant ${context.packageName} $permission")
                }
            }
            val remaining = names.filterNot(::isGranted)
            mainHandler.post { callback(remaining) }
        }, "permission-privileged-grant").start()
    }

    /** Legacy active-admin status alone cannot grant runtime permissions; owner roles are required. */
    private fun grantAsDeviceOrProfileOwner(names: List<String>) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val policy = context.getSystemService(DevicePolicyManager::class.java) ?: return
            val packageName = context.packageName
            if (!policy.isDeviceOwnerApp(packageName) && !policy.isProfileOwnerApp(packageName)) return
            val adminComponents = policy.activeAdmins.orEmpty().filter { it.packageName == packageName }
            names.forEach { permission ->
                adminComponents.forEach { admin ->
                    policy.setPermissionGrantState(
                        admin,
                        packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                }
            }
        } catch (_: Exception) {
            // Continue to root grant or the normal runtime prompt on devices that reject DPM access.
        }
    }

    /** Returns (exit code, combined output), or null if su is unavailable or times out. */
    private fun runSuCommand(command: String): Pair<Int, String>? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val deadline = SystemClock.elapsedRealtime() + ROOT_COMMAND_TIMEOUT_MS
        var exitCode: Int? = null
        while (exitCode == null && SystemClock.elapsedRealtime() < deadline) {
            try { exitCode = process.exitValue() }
            catch (_: IllegalThreadStateException) { Thread.sleep(100) }
        }
        if (exitCode == null) {
            process.destroy()
            null
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            exitCode to output
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val ROOT_COMMAND_TIMEOUT_MS = 30_000L
    }

    private fun finish(result: PermissionResult) {
        val callback = active?.second
        active = null; activeRuntimeNames = emptyList()
        try { callback?.invoke(result) }
        finally { pump() }
    }

    private fun result(type: PermissionType, status: PermissionStatus, granted: Boolean, permanent: Boolean,
                       canRequestAgain: Boolean, message: String?) = PermissionResult(
        permission = type, status = status, isGranted = granted, isPermanentlyDenied = permanent,
        canRequestAgain = canRequestAgain, requiresSettings = status == PermissionStatus.REQUIRES_SETTINGS || permanent,
        androidVersion = Build.VERSION.SDK_INT, message = message,
    )

    private fun isGranted(name: String): Boolean = try {
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }
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

    private fun isSpecialGrantedSafely(type: PermissionType): Boolean = try {
        when (type) {
            PermissionType.SYSTEM_ALERT_WINDOW -> Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
            PermissionType.WRITE_SETTINGS -> Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(context)
            PermissionType.MANAGE_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT < 30 || EnvironmentCompat.isExternalStorageManager()
            PermissionType.PACKAGE_USAGE_STATS -> hasAppOp("android:get_usage_stats")
            PermissionType.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> Build.VERSION.SDK_INT < 23 ||
                    (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                        ?.isIgnoringBatteryOptimizations(context.packageName) == true
            PermissionType.SCHEDULE_EXACT_ALARM -> Build.VERSION.SDK_INT < 31 ||
                    context.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true
            PermissionType.NOTIFICATION_LISTENER -> isNotificationListenerEnabled()
            PermissionType.REQUEST_INSTALL_PACKAGES -> Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
            PermissionType.USE_FULL_SCREEN_INTENT -> Build.VERSION.SDK_INT < 34 ||
                    context.getSystemService(android.app.NotificationManager::class.java)?.canUseFullScreenIntent() == true
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledComponents = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it) }.toSet()
        if (enabledComponents.isEmpty()) return false

        return notificationListenerComponents().any(enabledComponents::contains)
    }

    private fun notificationListenerComponents(): Set<ComponentName> = try {
        context.packageManager
            .queryIntentServices(
                Intent(NotificationListenerService.SERVICE_INTERFACE).setPackage(context.packageName),
                PackageManager.GET_SERVICES,
            )
            .mapNotNull { it.serviceInfo }
            .filter { it.permission == Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE }
            .map { ComponentName(context.packageName, it.name) }
            .toSet()
    } catch (_: Exception) {
        emptySet()
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
