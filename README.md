# Universal Permission Library Sample

A small Android app showing the library's runtime permission flow. Tap **Request camera** or **Request microphone** to display the Android system prompt. The app reports granted, denied, or permanently denied results; permanently denied requests offer a button to open app settings. The combined button queues camera and microphone requests through `requestMultiple`.

## Open and run

Open this folder in Android Studio, allow Gradle sync, then run the `app` configuration on an Android 6.0+ device or emulator. The project uses compile SDK 36, target SDK 35, Java 17, AGP 8.13.2, and Kotlin 2.2.20.

From a terminal, run `gradlew.bat :app:assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. On first install, tap a request button to show the Android permission prompt. Repeated denials may lead Android to stop showing the prompt; the app then offers its app-settings page.

The `app/libs` folder contains the library's core, platform, and model release AARs. The app manifest declares only camera and microphone, the two capabilities this sample requests.

## Flow

`PermissionManager` is constructed in `onCreate`, before the Activity reaches STARTED, so its Activity Result launchers are registered with the lifecycle. Tapping a request button calls `request()` or `requestMultiple()`. The permission callback updates the on-screen result; after a permanent denial, the user can choose to open this app's settings page.
