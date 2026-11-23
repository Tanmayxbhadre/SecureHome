import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Versioning: derived from the UTC time of the latest git commit, so every build of a commit
// gets the same version.
//   versionName = yymmdd.HHMM               e.g. 261122.0909
//   versionCode = minutes since 2020-01-01  (always increasing; fits Android's int limit)
// Falls back to the current time when git history isn't available (e.g. a source tarball).
val versionTime: Instant = runCatching {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%ct")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toLong().let(Instant::ofEpochSecond)
}.getOrElse { Instant.now() }

val appVersionName: String =
    DateTimeFormatter.ofPattern("yyMMdd.HHmm").withZone(ZoneOffset.UTC).format(versionTime)
val appVersionCode: Int =
    ((versionTime.epochSecond - Instant.parse("2020-01-01T00:00:00Z").epochSecond) / 60).toInt()

// Release signing comes from ~/.securehome/keystore.properties (made by scripts/make-release-key.sh),
// or the file named by $SECUREHOME_SIGNING. Without it, release builds come out unsigned.
val releaseSigning = Properties().apply {
    val f = file(System.getenv("SECUREHOME_SIGNING")
        ?: System.getenv("TAAR_SIGNING")
        ?: "${System.getProperty("user.home")}/.securehome/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Your relay's URL, baked in as the default on the setup screen. Set it per machine in
// local.properties (securehome.relay=https://securehome.<you>.workers.dev) or $SECUREHOME_RELAY_URL. Empty = ask on setup.
val defaultRelay: String = (System.getenv("SECUREHOME_RELAY_URL")
    ?: System.getenv("TAAR_RELAY_URL")
    ?: Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }.let { it.getProperty("securehome.relay") ?: it.getProperty("taar.relay") } ?: "").trim()

android {
    namespace = "com.tanmay.securehome"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tanmay.securehome"
        minSdk = 31
        // Stay on 36: Android 17 holds OTP SMS back for three hours from apps targeting 37+.
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "DEFAULT_RELAY", "\"$defaultRelay\"")
    }

    signingConfigs {
        if (!releaseSigning.isEmpty) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                // Sign with every scheme: some installers require the legacy v1 (JAR) signature too,
                // which AGP otherwise skips for minSdk >= 24.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            resValue("string", "app_name", "SecureHome")
            buildConfigField("boolean", "TEST_HOOKS", "false")
        }
        debug {
            // Debug builds install alongside the real app and can talk to a local development relay.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "SecureHome debug")
            buildConfigField("boolean", "TEST_HOOKS", "true")
        }
        // The release code exactly as shipped (R8-shrunk, not debuggable), plus the debug test hooks
        // (plain HTTP to a local relay, fake SMS from adb), signed with the debug key. Use it to check
        // that shrinking didn't break anything before installing a release build on a phone.
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            resValue("string", "app_name", "SecureHome QA")
            buildConfigField("boolean", "TEST_HOOKS", "true")
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("qa") {
        manifest.srcFile("src/debug/AndroidManifest.xml")
        java.srcDir("src/debug/java")
        res.srcDir("src/debug/res")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.all {
            it.systemProperty("securehome.root", rootDir.parentFile.absolutePath)
            it.systemProperty("taar.root", rootDir.parentFile.absolutePath)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.code.scanner)
    implementation(libs.zxing.core) // draws the join QR code
    implementation(libs.okhttp) // WebSocket for Get OTP

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
