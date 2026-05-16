plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Mirror the phone module's version strategy so the two side-load to
// a paired device without manifest-version churn. Reuse the *same*
// version.properties so Play sees the Watch APK as moving in lockstep
// with the phone APK (Play won't accept matching versionCodes from
// two different APKs of the same applicationId — the watch uses a
// distinct applicationId for that reason).
import java.util.Properties
val phoneVersionPropsFile = rootProject.file("version.properties")
val phoneVersionProps = Properties().apply {
    if (phoneVersionPropsFile.exists()) {
        phoneVersionPropsFile.inputStream().use(::load)
    }
}
val sharedVersionCode: Int = (phoneVersionProps.getProperty("versionCode") ?: "1").toInt()

android {
    namespace = "me.nettrash.geo.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.nettrash.geo"      // pair-with-phone — Wear OS uses
                                                // the SAME applicationId so the
                                                // Play store ties them together
        minSdk = 30                             // Wear OS 3+ matches sceneview targets
        targetSdk = 36
        versionCode = sharedVersionCode
        versionName = "1.0"
    }

    buildTypes {
        release {
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

    // Shared snapshot model + serialization with the phone module.
    implementation(libs.kotlinx.serialization.json)
}
