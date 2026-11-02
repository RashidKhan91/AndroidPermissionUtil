# Universal Android Permission Management

A Kotlin Android library that centralizes permission compatibility, runtime prompts, special Settings access, and structured results. The repository contains `permission-model`, `permission-platform`, `permission-core`, and `permission-sample`. It compiles against API 36 and supports Android 6.0 (API 23) and later.

## Modules

- **permission-model**: permission identifiers and result types.
- **permission-platform**: the centralized API-level mapping.
- **permission-core**: lifecycle-registered Activity Result launchers and request orchestration.
- **permission-sample**: runnable UI demonstrating individual, multiple, and special access requests.

## Build and AAR

Open this directory in Android Studio (AGP 8.13.2, Kotlin 2.2.20, JDK 17, Android SDK 36). Build the library with:

```shell
./gradlew :permission-core:assembleRelease
```

The core AAR is generated at `permission-core/build/outputs/aar/permission-core-release.aar`. It exposes the model and platform modules as Gradle API dependencies; a repository publication carries this dependency metadata. If distributing local AAR files manually, provide all three library AARs. On Windows use `gradlew.bat :permission-core:assembleRelease`.

## Add to an app

For source integration, include the modules and depend on `implementation(project(":permission-core"))`. For local AAR integration, copy all three release AARs (`permission-core`, `permission-platform`, and `permission-model`) into the host project's `libs` directory and add:

```kotlin
dependencies {
    implementation(files("libs/permission-core-release.aar", "libs/permission-platform-release.aar", "libs/permission-model-release.aar"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.core:core-ktx:1.16.0")
}
```

For team distribution, publish the three modules to a Maven repository so Gradle can resolve their dependency metadata.

## Publish from GitHub with JitPack

This is a multi-module Android library. Publish the **root project** to GitHub; JitPack will build its release variants. The `permission-sample` module is a demo app and is not the library dependency. Before the first release, replace `RashidKhan91` and `AndroidPermissionUtil` in the root `build.gradle.kts` with the GitHub owner and repository name. The project is configured to publish `permission-core`, `permission-platform`, and `permission-model` as Maven artifacts. The core publication declares the other library modules transitively.

1. Create a public GitHub repository and push this entire project, including the Gradle wrapper and `jitpack.yml`.
2. Set the version in the root `build.gradle.kts` (currently `1.0.0`). Commit and push.
3. Create and push a matching Git tag, for example `1.0.0`.
4. Open JitPack, enter the GitHub owner/repository, and build the `1.0.0` tag. Confirm that the build log succeeds.
5. Consumers add JitPack to their repositories and depend on the core artifact:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://www.jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.RashidKhan91.AndroidPermissionUtil:permission-core:1.0.0")
}
```

The app still declares its own feature permissions in its manifest. JitPack builds GitHub projects on demand; each release tag is the version developers request. See the [JitPack Android publishing guide](https://docs.jitpack.io/android/) and [multi-module artifact guide](https://docs.jitpack.io/building/).

Declare only the Android permissions needed by your app in its own manifest. The library intentionally contributes no `<uses-permission>` elements. Runtime requests fail with an actionable `ERROR` if a required permission was not declared.

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

Register the manager during `Activity.onCreate` or `Fragment.onCreate` before the lifecycle reaches STARTED, and retain it for that owner. This timing is required by Android's Activity Result API.

```kotlin
class CameraActivity : ComponentActivity() {
    private lateinit var permissionManager: PermissionManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)
        permissionManager.request(PermissionType.CAMERA) { result ->
            when (result.status) {
                PermissionStatus.GRANTED -> useCamera()
                PermissionStatus.DENIED -> explainAndRetry()
                PermissionStatus.PERMANENTLY_DENIED -> permissionManager.openAppSettings()
                else -> showMessage(result.message)
            }
        }
    }
}
```

Fragments can use `PermissionManager(this)` from `onCreate`. The manager serializes requests and prevents overlapping launcher calls. Multiple requests return one result per distinct requested capability:

```kotlin
permissionManager.requestMultiple(listOf(PermissionType.CAMERA, PermissionType.RECORD_AUDIO)) { batch ->
    batch.results.forEach { result -> println("${result.permission}: ${result.status}") }
}
```

Special access uses its own Android Settings flow and returns after the host resumes from Settings:

```kotlin
permissionManager.request(PermissionType.SYSTEM_ALERT_WINDOW) { result ->
    if (result.isGranted) drawOverlay() else explainSettingsAccess()
}
```

## Results

`PermissionResult` contains the requested `PermissionType`, `PermissionStatus`, grant/permanent-denial flags, retry/settings guidance, device API level, and an optional message. `REQUIRES_SETTINGS` is used by `check()` for special access that is not currently enabled. A completed settings trip returns `GRANTED` or `DENIED`. `NOT_AVAILABLE` means the OS has no corresponding permission on that API level. `ERROR` includes missing manifest declarations or a failed Settings launch. Android does not expose a public, general permission-policy flag to third-party apps, so this implementation does not guess that a denial is caused by an administrator; `RESTRICTED` remains available for future platform-specific detection. `PARTIALLY_GRANTED` reports approximate location when precise location was requested on Android 12+, or selected photo/video access when full media access was requested on Android 14+.

Permanent-denial detection is necessarily best-effort: Android does not expose a universal “never ask again” bit. The manager requires a prior request and a false rationale result. OEM behavior, auto-reset, one-time grants, and administrator policy can affect interpretation. Recheck using `check()` when the app returns to the foreground after your own settings navigation.

## Compatibility overview

| Android | API | Behavior covered |
|---|---:|---|
| 6.0 | 23 | Dangerous runtime permissions; overlay/write-settings app access |
| 10 | 29 | Background location permission introduced |
| 11 | 30 | All-files access special Settings flow |
| 12 | 31 | Bluetooth scan/connect/advertise; exact alarm access on API 31+ |
| 13 | 33 | Notifications, granular media, nearby Wi-Fi |
| 14 | 34 | Selected visual media and full-screen intent access |
| 15 | 35 | Current platform permission mappings; foreground service types remain app-specific |
| 16 | 36 | API 36 compiled/tested mappings; unmapped future restrictions reported by Android |

Pre-31 Bluetooth scanning and pre-33 nearby Wi-Fi map to location permission. API 34 selected photos is represented by `SELECTED_PHOTOS`; use Android's Photo Picker for one-off media selection where possible. Background location should be requested separately after foreground location and needs an app-specific rationale. Android 10+ storage is better handled with the Storage Access Framework or Photo Picker; this library does not wrap document picker UX. `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` are legacy mappings only. Full storage access is special and subject to Google Play policy. Notification listener access is granted only via the dedicated Settings screen. Manufacturer/model are available to the host app from `Build`; this library does not change behavior based on OEM identity because permission policy should follow Android APIs.

## Manifest reference

Declare just what your feature needs. Common entries include `CAMERA`, `RECORD_AUDIO`, location, contacts, phone/SMS, `READ_MEDIA_*`, `READ_MEDIA_VISUAL_USER_SELECTED`, Bluetooth, nearby Wi-Fi, and `POST_NOTIFICATIONS`. Add legacy storage declarations with `android:maxSdkVersion` where appropriate. Special access also requires declarations such as `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, `MANAGE_EXTERNAL_STORAGE`, `PACKAGE_USAGE_STATS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SCHEDULE_EXACT_ALARM`, `REQUEST_INSTALL_PACKAGES`, and `USE_FULL_SCREEN_INTENT`; notification listener access requires a service protected by `BIND_NOTIFICATION_LISTENER_SERVICE`. Review Play policy and each feature's Android documentation before declaring restricted permissions. See the sample manifest for a comprehensive demonstration; do not copy every declaration into a production app.

## Troubleshooting

- **`ERROR` with “Declare … in the host app manifest”**: add that permission to the application module manifest and rebuild/reinstall.
- **Activity Result registration error**: construct the manager before STARTED, normally in `onCreate`; do not construct it in response to a click.
- **Special access returns denied**: the user did not enable access, or the device/OEM does not expose the expected settings route. Check again on foreground and provide a manual path.
- **Permission not available**: use the platform alternative described above (Photo Picker, SAF, or feature-specific API).
- **Restricted**: an admin or OS policy may block the permission; the app cannot override this.

## Tests

Run compatibility mapping unit tests with `./gradlew :permission-platform:testDebugUnitTest`. The tests cover API 32/33 media changes, API 34 selected photos, and API 30/31 Bluetooth mapping. Device-specific Settings and permission-dialog behavior should additionally be exercised in instrumentation tests on representative OS versions; those cannot be faithfully simulated by the local mapping tests.
