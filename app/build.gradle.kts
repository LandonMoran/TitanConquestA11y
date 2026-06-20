plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.titanconquest.a11y"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.titanconquest.a11y"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "${rootDir}/app/titanconquest.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "titanconquest123"
            keyAlias = System.getenv("KEY_ALIAS") ?: "titanconquest"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "titanconquest123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    lint { abortOnError = false }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
}
