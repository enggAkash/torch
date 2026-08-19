import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.engineerakash.torch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.engineerakash.torch"
        minSdk = 23
        targetSdk = 37
        versionCode = 9
        versionName = "2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Whitelist of shipped locales; also keeps AppCompat/Material/Play Services
        // resources limited to these instead of their ~80 locale variants. Every
        // values-* folder needs a matching entry here or aapt2 silently strips it.
        localeFilters += listOf(
            "en", "it", "de", "fr", "es", "nl", "ru", "ja", "b+zh+Hans", "ar",
            "hi", "bn", "gu", "mr", "pa", "ta", "te", "kn", "ml", "ur",
            "sw", "af", "zu", "xh", "ha", "ms", "b+fil", "my",
            "ps", "b+fa+AF", "ku", "b+ckb",
            // "iw" is Android's legacy code for Hebrew; "nb" is Norwegian Bokmål
            "nb", "da", "sv", "fi", "ko", "iw",
        )
        // Emits android:localeConfig from the locales above so Android 13+ offers
        // the app's languages in the system per-app language setting.
        generateLocaleConfig = true
    }

    buildTypes {
        debug {
            // Google's sample banner unit — never serves live ads.
            // https://developers.google.com/admob/android/test-ads
            buildConfigField("String", "AD_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"")

            // en-XA/ar-XB test locales for text-expansion and RTL smoke tests
            isPseudoLocalesEnabled = true
        }
        release {
            buildConfigField("String", "AD_UNIT_ID", "\"ca-app-pub-5354242864643274/8713292011\"")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Embeds native symbol tables in the AAB so Play can symbolicate native
            // crashes/ANRs. Currently inert: the app's only native lib
            // (libdatastore_shared_counter.so, via play-services-ads and Firebase)
            // ships pre-stripped, so Play's "no debug symbols" warning is
            // unavoidable and safe to ignore. Upload-only metadata either way —
            // never affects the size users download.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.play.services.ads)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))

    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
}