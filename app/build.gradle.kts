plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// --- CI build inputs (all optional; defaults preserve local/dev behaviour) ---
// versionCode: CI sets VERSION_CODE=$CI_PIPELINE_IID so every Play upload climbs.
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
// Release signing: CI decodes a base64 upload keystore to SIGNING_KEYSTORE_FILE.
// Absent locally -> release stays unsigned (fine for dev; Play needs the key).
val releaseKeystore = System.getenv("SIGNING_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }

// --- Server defaults (neutral by default; overridable) ---
// This is a bring-your-own-server client: the real server is supplied at
// runtime via the Add-Server flow (/api/config + OIDC discovery). The build
// config fields below are only an internal, defensive in-memory fallback used
// before a server is connected — no UI surfaces them. The committed defaults
// are intentionally EMPTY so the public source leaks no host. Operators who
// build their own distribution can inject real values without editing source,
// e.g. -PoidcIssuer=... -PapiBaseUrl=... -PserverPreset=... (or in
// gradle.properties / a local properties file).
val oidcIssuer = (project.findProperty("oidcIssuer") as? String) ?: ""
val apiBaseUrl = (project.findProperty("apiBaseUrl") as? String) ?: ""
// Add-Server one-tap preset. Empty everywhere by default (neutral); an operator
// build can prefill it. SERVER_PRESET stays "" in committed source.
val serverPreset = (project.findProperty("serverPreset") as? String) ?: ""

// Published applicationId base. Forks override with -PchinoAppId=... (or in
// gradle.properties); defaults to the project's OSS reverse-DNS id from its
// GitHub org Pages (io.github.zaentrum ← zaentrum.github.io) — portable on
// hand-off, not tied to any operator domain. The Kotlin `namespace` below is the
// internal code namespace and is intentionally separate (still cloud.nalet.chino.tv).
val chinoAppId = (project.findProperty("chinoAppId") as? String) ?: "io.github.zaentrum.chino"

android {
    namespace = "cloud.nalet.chino.tv"
    compileSdk = 35

    defaultConfig {
        // Single unified app id — no product flavors. Forks override via
        // -PchinoAppId; debug builds append ".debug" (see buildTypes below).
        applicationId = chinoAppId
        minSdk = 21
        targetSdk = 35
        // Unified Play app (io.github.zaentrum.chino): the TV AAB shares one listing
        // with chino-mobile's AAB, and every artifact in a listing needs a
        // UNIQUE versionCode. The TV build lives in a disjoint high range so it
        // never collides with the mobile pipeline's raw IID; Play still serves
        // each device its form factor by manifest features (leanback vs touch),
        // not by versionCode.
        versionCode = (ciVersionCode ?: 4) + 2_000_000
        versionName = "0.1.3"
        resValue("string", "app_name", "Chino")

        // Neutral bring-your-own-server client: everything below is an internal,
        // non-UI fallback only (empty by default). The connected server's
        // /api/config + OIDC discovery supplies the real values at runtime.
        buildConfigField("String", "OIDC_ISSUER", "\"$oidcIssuer\"")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Add-Server prefill: blank (neutral) — empty field, generic placeholder,
        // no baked operator URL.
        buildConfigField("String", "SERVER_PRESET", "\"$serverPreset\"")
        buildConfigField("String", "OIDC_CLIENT_ID", "\"chino\"")
        buildConfigField("String", "OIDC_AUDIENCE", "\"chino-web\"")
        // No flavors anymore; kept as a stable tag for telemetry / bug reports.
        buildConfigField("String", "FLAVOR_NAME", "\"prod\"")
    }

    signingConfigs {
        // Optional shared DEBUG keystore: if you drop a `debug.keystore` next to
        // this file, every build (local + CI) shares one debug signature so
        // `adb install -r` updates in place without wiping app data. Debug keys
        // are public by design, so this is never committed — when absent AGP
        // auto-generates the standard per-machine debug key, which is fine for
        // development. The release/upload key comes from CI env vars below.
        val debugKeystore = file("debug.keystore").takeIf { it.exists() }
        if (debugKeystore != null) {
            getByName("debug") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Supplied by CI via env (base64 upload keystore -> SIGNING_KEYSTORE_FILE).
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    lint {
        // lintVitalRelease crashes under AGP 8.7 lint + Kotlin 2.1 analysis API
        // (NonNullableMutableLiveDataDetector: "KaCallableMemberCall ... interface
        // was expected"). Lint is code-quality gating, not part of producing the
        // artifact, so skip it on release builds to keep the AAB/APK build green.
        checkReleaseBuilds = false
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
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/io.netty.versions.properties",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.icons.lucide)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.tv.material)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.coil.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    // Zap client-side prefetch: SimpleCache + CacheDataSource + CacheWriter live
    // in media3-datasource; StandaloneDatabaseProvider in media3-database. Both
    // already resolve transitively via exoplayer/okhttp, but pinned explicitly
    // so the prefetch cache stack survives a transitive-graph change.
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)

    implementation(libs.zxing.core)
    implementation(libs.androidx.security.crypto)
}
