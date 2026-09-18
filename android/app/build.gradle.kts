import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("kotlin-kapt")
}

// Release keystore credentials are read from local.properties (gitignored) so they are
// never committed. Required keys: RELEASE_STORE_PASSWORD, RELEASE_KEY_PASSWORD.
// An environment variable with the same name is accepted as a fallback (e.g. for CI).
val localProperties: Properties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { load(it) }
    }
}

fun signingProperty(key: String): String? =
    (localProperties.getProperty(key)?.trim() ?: System.getenv(key)?.trim())?.takeIf { it.isNotEmpty() }

val releaseStorePassword: String? = signingProperty("RELEASE_STORE_PASSWORD")
val releaseKeyPassword: String? = signingProperty("RELEASE_KEY_PASSWORD")
val hasReleaseSigning: Boolean = releaseStorePassword != null && releaseKeyPassword != null
if (!hasReleaseSigning) {
    logger.warn(
        "Release signing credentials missing (RELEASE_STORE_PASSWORD / RELEASE_KEY_PASSWORD in " +
            rootProject.file("local.properties").absolutePath + " or env). " +
            "Debug builds will use the default debug key; release builds will be unsigned."
    )
}

android {
    namespace = "com.darkmessage.app"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file("dark-message-release.jks")
                storePassword = releaseStorePassword
                keyAlias = "darkmessage"
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.darkmessage.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    buildTypes {
        debug {
            // Signed with the release key so a local debug APK installs over the store build.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Lets the About screen read the real versionName instead of a hand-typed
        // string that went stale at 1.2.0 while the app shipped as 1.3.0.
        buildConfig = true
    }

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.appcompat)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security
    implementation(libs.tink.android)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Activity
    implementation(libs.activity.compose)

    // Image loading
    implementation(libs.coil.compose)

    // Camera (QR scanning) - CameraX only, no ML Kit and no Google Play services, so the
    // scanner also works on devices without GMS (RuStore).
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // QR encoding / decoding (pure Java; runs in JVM unit tests too)
    implementation(libs.zxing.core)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

kapt {
    correctErrorTypes = true
}

// -------------------------------------------------------------------------------------------------
// Offline / no-GMS guards (spec section 6, item 12).
//
// Dark Message has no network code and must keep working on devices without Google Play services
// (it ships on RuStore). These two tasks make the build itself fail if that ever stops being true,
// instead of trusting a code review to notice a transitive dependency.
// -------------------------------------------------------------------------------------------------

val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE"
)

val forbiddenDependencyGroups = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    // Looks GMS-free but ships its own binary and is not allowed here.
    "com.google.mlkit"
)

// AGP writes the merged manifest to intermediates/merged_manifest/release/<task>/ and
// intermediates/merged_manifests/release/<task>/ depending on the consumer.
val mergedReleaseManifests = fileTree(layout.buildDirectory.dir("intermediates")) {
    include("**/merged_manifest*/**/release/**/AndroidManifest.xml")
    include("**/merged_manifests/release/**/AndroidManifest.xml")
}

tasks.register("verifyNoNetworkPermissions") {
    group = "verification"
    description = "Fails the build if a merged release manifest declares a network permission."
    doLast {
        val manifests = mergedReleaseManifests.files.filter { it.isFile }
        if (manifests.isEmpty()) {
            logger.lifecycle("verifyNoNetworkPermissions: no merged release manifest found yet.")
            return@doLast
        }
        val offenders = manifests.flatMap { manifest ->
            val text = manifest.readText()
            forbiddenPermissions
                .filter { permission -> text.contains("\"" + permission + "\"") }
                .map { permission -> manifest.absolutePath + ": " + permission }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Dark Message must stay fully offline, but the merged release manifest declares:\n" +
                    offenders.joinToString("\n")
            )
        }
        logger.lifecycle(
            "verifyNoNetworkPermissions: OK, no network permission in " +
                manifests.size + " merged manifest(s)."
        )
    }
}

// AGP 8 splits manifest merging into processReleaseMainManifest and
// processReleaseManifestForPackage; match both instead of a single hardcoded task name.
tasks.matching { it.name.startsWith("processRelease") && it.name.contains("Manifest") }
    .configureEach { finalizedBy("verifyNoNetworkPermissions") }

tasks.register("verifyNoGms") {
    group = "verification"
    description =
        "Fails the build if a Google Play services / Firebase artifact reaches the release runtime."
    doLast {
        val components = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents
        val offenders = components.mapNotNull { component ->
            val id = component.moduleVersion
            if (id != null && forbiddenDependencyGroups.any {
                    id.group == it || id.group.startsWith(it + ".")
                }
            ) {
                id.group + ":" + id.name + ":" + id.version
            } else {
                null
            }
        }.sorted()
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "The app must run on devices without Google Play services, but the release " +
                    "runtime classpath contains:\n" + offenders.joinToString("\n")
            )
        }
        logger.lifecycle(
            "verifyNoGms: OK, no GMS/Firebase artifact among " + components.size + " components."
        )
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn("verifyNoGms") }
