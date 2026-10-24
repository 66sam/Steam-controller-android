plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.scmapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.scmapper"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.3.0"
    }

    // A fixed key kept in the repo, so every build (local or CI) can be installed as an update
    // over the previous one. This is a sideloaded hobby app, not a Play Store release key.
    signingConfigs {
        create("shared") {
            storeFile = file("scmapper.keystore")
            storePassword = "scmapper"
            keyAlias = "scmapper"
            keyPassword = "scmapper"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Shizuku lets the app run a small helper with shell (adb) privileges, which can
    // create virtual HID devices through /dev/uhid.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
