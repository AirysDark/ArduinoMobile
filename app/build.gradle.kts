plugins {
    id("com.android.application")
}

android {
    namespace = "com.airysdark.arduinomobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.airysdark.arduinomobile"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
