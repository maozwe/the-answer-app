plugins {
    id("com.android.application")
}

// The release workflow sets VERSION_NAME (the version manage-control assigned, e.g. 1.4.2) and the
// signing key. versionCode follows it and stays above the 1.1xx builds of the GitHub-release era; the
// app updates itself only to a higher version signed with the same key.
val appVersion = System.getenv("VERSION_NAME") ?: "0.0.1"
val code = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> 1000 + a * 1_000_000 + b * 1_000 + c }
val keystore = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.maoz.theanswer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maoz.theanswer"
        minSdk = 26
        targetSdk = 36
        versionCode = code
        versionName = appVersion
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
