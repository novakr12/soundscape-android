plugins {
    id("com.android.application")
}

val keystore = System.getenv("SOUNDSCAPE_KEYSTORE")

android {
    namespace = "io.github.novakr12.soundscape"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.novakr12.soundscape"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.10.5"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SOUNDSCAPE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SOUNDSCAPE_KEY_ALIAS")
                keyPassword = System.getenv("SOUNDSCAPE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.work:work-runtime:2.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
