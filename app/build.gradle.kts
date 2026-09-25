plugins {
    id("com.android.application")
}

// The release workflow sets VERSION_CODE (the run number) and the signing key; the app updates
// itself only to a higher versionCode signed with the same key.
val code = (System.getenv("VERSION_CODE") ?: "2").toInt()
val keystore = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.maoz.theanswer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maoz.theanswer"
        minSdk = 26
        targetSdk = 36
        versionCode = code
        versionName = "1.$code"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
