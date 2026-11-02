plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.universal.permissions.sample"
    compileSdk = 36
    defaultConfig { applicationId = "dev.universal.permissions.sample"; minSdk = 23; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":permission-core")); implementation("androidx.activity:activity-ktx:1.10.1") }
