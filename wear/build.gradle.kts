plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The watch ships under the SAME applicationId as the phone (Wear OS's
// paired-listing model — Play ties the two APKs together by applicationId).
// Play requires those two APKs to carry DISTINCT versionCodes, so the watch
// takes the phone's shared versionCode plus a fixed large offset: it stays
// distinct from — and can never collide with — the auto-incrementing phone code
// across releases, while still moving forward every release. versionName tracks
// the phone's so the paired apps report the same human version.
import java.util.Properties
val phoneVersionPropsFile = rootProject.file("version.properties")
val phoneVersionProps = Properties().apply {
    if (phoneVersionPropsFile.exists()) {
        phoneVersionPropsFile.inputStream().use(::load)
    }
}
val sharedVersionCode: Int = (phoneVersionProps.getProperty("versionCode") ?: "1").toInt()
// Offset puts the watch versionCode in a range the phone code (low thousands,
// auto-bumped each build) can never reach, so the two never collide on Play.
val watchVersionCode: Int = sharedVersionCode + 1_000_000
// KEEP THIS DEFAULT IN STEP WITH `Geo/build.gradle.kts` — the two modules hold
// the marketing version independently, so a release that bumps only the phone
// ships the watch under the previous version (1.2 nearly went out with a 1.1
// watch AAB). Better: move `versionName` into `version.properties` next to
// `versionCode` and have both modules fall back to it.
val resolvedVersionName: String =
    (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.2"

// Release signing — resolve the SAME keystore the phone module uses, from
// keystore.properties (dev machines) or GEO_* env vars (CI). Null when neither
// is present, so a local assembleRelease still works via the debug-signing
// fallback (it just isn't uploadable to Play). Mirrors Geo/build.gradle.kts.
val releaseSigning: Map<String, String>? = run {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val p = Properties().apply { propsFile.inputStream().use(::load) }
        mapOf(
            "storeFile" to (p.getProperty("storeFile") ?: return@run null),
            "storePassword" to (p.getProperty("storePassword") ?: return@run null),
            "keyAlias" to (p.getProperty("keyAlias") ?: return@run null),
            "keyPassword" to (p.getProperty("keyPassword") ?: return@run null),
        )
    } else {
        val path = System.getenv("GEO_KEYSTORE_PATH")
        val storePassword = System.getenv("GEO_KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("GEO_KEY_ALIAS")
        val keyPassword = System.getenv("GEO_KEY_PASSWORD")
        if (path != null && storePassword != null && keyAlias != null && keyPassword != null) {
            mapOf(
                "storeFile" to path,
                "storePassword" to storePassword,
                "keyAlias" to keyAlias,
                "keyPassword" to keyPassword,
            )
        } else null
    }
}

android {
    namespace = "me.nettrash.geo.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.nettrash.geo"      // pair-with-phone — Wear OS uses
                                                // the SAME applicationId so the
                                                // Play store ties them together
        minSdk = 30                             // Wear OS 3+ matches sceneview targets
        targetSdk = 36
        versionCode = watchVersionCode          // distinct from the phone (see offset above)
        versionName = resolvedVersionName       // tracks the phone's human version
    }

    signingConfigs {
        releaseSigning?.let { sig ->
            create("release") {
                storeFile = rootProject.file(sig.getValue("storeFile"))
                storePassword = sig.getValue("storePassword")
                keyAlias = sig.getValue("keyAlias")
                keyPassword = sig.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Same keystore as the phone; null falls back to debug signing so a
            // local assembleRelease still works (just not uploadable to Play).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)

    implementation(platform(libs.androidx.compose.bom))
    // Wear OS uses its own material/foundation packages.
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)

    // Wearable Data Layer — paired-phone communication.
    implementation(libs.play.services.wearable)

    // Wear Tiles — glanceable tile cards, the Wear-OS analog of
    // iOS complication families.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    implementation(libs.androidx.wear.protolayout.expression)
    // TileService returns ListenableFuture; guava provides it +
    // kotlinx-coroutines-guava gives us the `future { … }` builder.
    implementation(libs.guava.listenablefuture)
    implementation(libs.kotlinx.coroutines.guava)

    // Shared snapshot model + serialization with the phone module.
    implementation(libs.kotlinx.serialization.json)
}
