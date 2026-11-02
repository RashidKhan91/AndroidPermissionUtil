plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("maven-publish") }
android { namespace = "dev.universal.permissions.platform"; compileSdk = 36; defaultConfig { minSdk = 23 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":permission-model"))
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
}

android.publishing.singleVariant("release") { withSourcesJar() }

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()
                from(components["release"])
            }
        }
    }
}
