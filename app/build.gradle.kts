plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Il numero di versione arriva dalla build automatica su GitHub (-PversionCode=N).
// In Android Studio vale 1.
val buildNumber = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "it.stopscroll"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.stopscroll"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // Stessa firma per tutte le build: così ogni nuova versione si installa
    // sopra la precedente senza dover disinstallare.
    signingConfigs {
        create("stopscroll") {
            storeFile = file("stopscroll.jks")
            storePassword = "stopscroll"
            keyAlias = "stopscroll"
            keyPassword = "stopscroll"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stopscroll")
        }
        debug {
            signingConfig = signingConfigs.getByName("stopscroll")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
