plugins {
    id("com.android.application")
}

android {
    namespace = "com.zerotrace.browser"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.zerotrace.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK can be sideloaded straight away.
            // Replace with your own signing config for anything you distribute.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Tor ships as libtor.so; extract so it can be loaded on all devices.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.webkit:webkit:1.17.1")
    // Declared directly only so the manifest can switch off its EmojiCompat initializer.
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("info.guardianproject:tor-android:0.4.9.12")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
}
