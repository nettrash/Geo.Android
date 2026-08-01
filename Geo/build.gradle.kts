import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// versionCode strategy: read from `version.properties` at the repo root and
// auto-incremented after every successful assemble*/bundle* by the
// `bumpVersionCode` finalizer below. Mirrors the iOS `agvtool bump`
// post-build action — every Build button press bumps the number.
//
// The file IS tracked in git so the bump propagates between machines
// (commit it after a release).
//
// versionName is human (semver). Defaults to "1.1" but can be overridden
// at the command line with `-PversionName=1.2.3`.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use(::load)
    }
}
val storedVersionCode: Int = run {
    val raw = versionProps.getProperty("versionCode")
    if (raw == null) {
        // No file present (fresh checkout that forgot to commit
        // version.properties, or a shallow CI clone). Fail loudly here
        // instead of defaulting to a small number that Play will already
        // have reserved from a previous upload.
        throw GradleException(
            "version.properties is missing or has no `versionCode` entry. " +
                "Either commit ${versionPropsFile.relativeTo(rootProject.projectDir)} " +
                "to the repo, or pass `-PversionCode=N` (strictly greater than " +
                "every versionCode previously uploaded to Play)."
        )
    }
    val override = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    override ?: raw.toInt()
}

val resolvedVersionName: String =
    (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.2"

// Allow opting out of the bump for one build (useful for CI which doesn't
// want to mutate the tracked file on the runner): `-PnoBump`.
val skipVersionBump: Boolean = project.hasProperty("noBump")

// Resolve release signing material from (in order):
//   1. `keystore.properties` next to the root build file (developer machines).
//   2. GEO_KEYSTORE_PATH / GEO_KEYSTORE_PASSWORD / GEO_KEY_ALIAS /
//      GEO_KEY_PASSWORD environment variables (CI).
// Returns `null` when nothing is configured — the release build then falls
// back to the debug signing config so `assembleRelease` still works locally
// without keys (it just won't be uploadable to Play).
// Google Maps SDK key — injected into the manifest's
// `com.google.android.geo.API_KEY` meta-data via the MAPS_API_KEY
// manifestPlaceholder rather than committed as a literal. Read from
// `local.properties` at the repo root (which is gitignored, alongside
// keystore.properties / *.jks), defaulting to an empty string when absent
// so the build still configures (the Map tab just won't render tiles).
val localProperties: Properties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use(::load)
    }
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY") ?: ""

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
    namespace = "me.nettrash.geo"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.nettrash.geo"
        minSdk = 28
        targetSdk = 36
        versionCode = storedVersionCode
        versionName = resolvedVersionName

        // Supplies the manifest's com.google.android.geo.API_KEY meta-data
        // (`android:value="${MAPS_API_KEY}"`) from local.properties.
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
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
            // If `keystore.properties` / env-vars aren't present, this stays
            // null and AGP falls back to the debug signing config so local
            // `assembleRelease` still works (just not uploadable to Play).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        // AppLog gates debug() on BuildConfig.DEBUG, so we need the
        // generated BuildConfig class.
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric needs Android resources + the system AndroidManifest.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/*.so"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.work.compiler)

    // Maps
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    // Wear OS messaging (phone-side bridge to the paired watch).
    implementation(libs.play.services.wearable)

    // ARCore
    implementation(libs.arcore)
    implementation(libs.sceneview)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Serialization & Network
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Glance (widgets)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    debugImplementation(libs.androidx.ui.tooling)

    // Unit tests — JUnit 4 + Robolectric for anything touching android.* APIs.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
}

// ---- mlat_delta_2026.bin integrity gate ----------------------------------
//
// The AACGM-v2 correction grid is the highest-risk asset in the magnetic
// conditions feature: without it the raw centred dipole is 3-6 degrees too
// far poleward across the British Isles, western Europe, Iceland, the Urals
// and Australia — always in the over-promising direction — and Geo would
// tell a hiker in Britain "aurora tonight" at roughly Kp 2.5 when the truth
// is Kp 5+. `MLatDeltaGrid.decode` refuses anything that isn't exactly
// MLAT_GRID_BYTE_COUNT bytes, so a bad asset degrades safely at runtime,
// but it must never get as far as a build.
//
// There is no header in the file — the byte count IS the integrity check —
// so this gate checks exactly what the runtime does, plus an all-zero check
// that catches a truncate-to-zero or a git-lfs pointer that never resolved.
// The unit test pins the SHA-256; this task is the cheap version that runs
// on every build.
//
// Deliberately NOT declared as a task input: `inputs.file` on a missing
// file fails during snapshotting with a generic message, and the whole
// point here is the specific one. The check reads 4680 bytes, so running
// it every time costs nothing.
val mlatGridFile = layout.projectDirectory
    .file("src/main/assets/spaceweather/mlat_delta_2026.bin").asFile
val mlatGridByteCount = 4680L

val verifyMlatGrid = tasks.register("verifyMlatGrid") {
    group = "verification"
    description = "Fails the build if the AACGM correction grid is missing, the wrong size, or all-zero."
    outputs.upToDateWhen { false }
    doLast {
        if (!mlatGridFile.isFile) {
            throw GradleException(
                "Missing ${mlatGridFile.name}. The AACGM-v2 correction grid is required: " +
                    "without it the aurora verdict falls back to the raw dipole, which is " +
                    "5.63 degrees too far poleward at London. Regenerate it with " +
                    "Geo/tools/gen_mlat_delta.py in the iOS repo and copy it byte-identically " +
                    "to src/main/assets/spaceweather/."
            )
        }
        val actual = mlatGridFile.length()
        if (actual != mlatGridByteCount) {
            throw GradleException(
                "${mlatGridFile.name} is $actual bytes, expected exactly $mlatGridByteCount " +
                    "(65 latitude rows x 72 longitude columns, one signed byte per node). " +
                    "The byte count is the file's only integrity check — a wrong size means " +
                    "the wrong grid, not a recoverable one."
            )
        }
        if (mlatGridFile.readBytes().all { it == 0.toByte() }) {
            throw GradleException(
                "${mlatGridFile.name} is all zeroes, which silently reduces every corrected " +
                    "magnetic latitude to the raw dipole. That is the exact failure the file " +
                    "exists to prevent."
            )
        }
    }
}

// `preBuild` is the module-level anchor every variant's build hangs off.
// Matched by name rather than looked up eagerly so this survives however
// AGP orders its own registration.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyMlatGrid)
}

// ---- IDE compatibility: `unitTestClasses` / `androidTestClasses` aliases ----
//
// AGP 9 stopped creating the legacy aggregate `unitTestClasses` and
// `androidTestClasses` tasks and only registers the variant-specific
// compile tasks (`compileDebugUnitTestKotlin`,
// `compileDebugAndroidTestKotlin`, etc). Android Studio's "Make
// Project" / Gradle sync still tries to invoke
//   :Geo:unitTestClasses
//   :Geo:androidTestClasses
// on some code paths and fails with
//   "Cannot locate tasks that match ':Geo:<x>TestClasses'".
// Register thin aliases so the IDE is happy. Pure aggregators — no
// actions of their own, just dependsOn the per-variant compile tasks.
afterEvaluate {
    if (tasks.findByName("unitTestClasses") == null) {
        tasks.register("unitTestClasses") {
            group = "verification"
            description =
                "Compatibility alias — depends on all variant-specific unit-test compile tasks."
            dependsOn(tasks.matching {
                val n = it.name
                n.startsWith("compile") &&
                    (n.endsWith("UnitTestKotlin") || n.endsWith("UnitTestJavaWithJavac"))
            })
        }
    }
    if (tasks.findByName("androidTestClasses") == null) {
        tasks.register("androidTestClasses") {
            group = "verification"
            description =
                "Compatibility alias — depends on all variant-specific instrumented-test compile tasks."
            dependsOn(tasks.matching {
                val n = it.name
                n.startsWith("compile") &&
                    (n.endsWith("AndroidTestKotlin") || n.endsWith("AndroidTestJavaWithJavac"))
            })
        }
    }
}

// ---- versionCode auto-bump ----------------------------------------------
//
// Mirrors the iOS `agvtool bump` post-build action: every successful
// `assembleDebug`, `assembleRelease`, `bundleDebug`, or `bundleRelease`
// rewrites `version.properties` with `versionCode + 1`. The new value is
// effective on the *next* build (the current build keeps the value it was
// configured with — defaultConfig is locked at configuration time).
//
// `doLast` only fires when the parent task's actions complete successfully,
// so failed builds don't bump. The AtomicBoolean guards against double-
// bumping when more than one of the listed tasks runs in a single
// invocation (e.g. `./gradlew assembleRelease bundleRelease`).
//
// Opt out per-build with `-PnoBump`.
val bumpedInThisInvocation = AtomicBoolean(false)
afterEvaluate {
    if (skipVersionBump) return@afterEvaluate
    listOf(
        "assembleDebug",
        "assembleRelease",
        "bundleDebug",
        "bundleRelease",
    ).forEach { taskName ->
        tasks.findByName(taskName)?.doLast {
            if (!bumpedInThisInvocation.compareAndSet(false, true)) return@doLast
            val newValue = storedVersionCode + 1
            versionProps.setProperty("versionCode", newValue.toString())
            versionPropsFile.outputStream().use {
                versionProps.store(
                    it,
                    "Auto-incremented after build. Edit only if you know what you're doing."
                )
            }
            logger.lifecycle(
                ":Geo: bumped versionCode $storedVersionCode -> $newValue (effective next build)"
            )
        }
    }
}
