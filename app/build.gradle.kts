import java.util.Properties
import java.io.FileInputStream
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.apkupdater"
    compileSdk = 35

    val buildNumber = System.getenv("BUILD_NUMBER").orEmpty()
    defaultConfig {
        applicationId = "com.apkupdater" + System.getenv("BUILD_TAG").orEmpty()
        minSdk = 21
        targetSdk = 35
        versionCode = 142
        versionName = if (buildNumber.isEmpty()) "3.7.0" else "0.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            try {
                val props = Properties()
                props.load(FileInputStream(rootProject.file("keystore.properties")))
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } catch (ignored: Exception) {
                val config = signingConfigs.getByName("debug")
                storeFile = config.storeFile
                storePassword = config.storePassword
                keyAlias = config.keyAlias
                keyPassword = config.keyPassword
                println("Signing config not found. Using debug settings.")
            }
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    applicationVariants.configureEach {
        outputs.configureEach {
            val variant = (this as BaseVariantOutputImpl)
            variant.outputFileName = defaultConfig.applicationId + "-" + buildType.name + ".apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        warning.addAll(arrayOf("ExtraTranslation", "MissingTranslation", "MissingQuantity"))
    }
}

// The kotlinOptions {} block this replaces has been deprecated since Kotlin 2.0 and is not
// guaranteed to exist in 2.3.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

configurations.all {
    // kotlin-parcelize-runtime, pulled in by gplayapi 3.6, ships every kotlinx.android.parcel
    // class that the long-retired kotlin-android-extensions-runtime 1.3.50 (2019) does. An old
    // transitive dependency still asks for the latter, and the two collide at packaging with
    // "Duplicate class kotlinx.android.parcel.Parcelize". The only classes lost by dropping the
    // old one are kotlinx.android.extensions.* — synthetic view binding, which nothing here uses.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
}

dependencies {

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.navigation:navigation-runtime-ktx:2.8.9")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.github.rumboalla.KryptoPrefs:kryptoprefs-gson:0.4.3")
    implementation("com.github.rumboalla.KryptoPrefs:kryptoprefs:0.4.3")
    implementation("com.github.topjohnwu.libsu:core:5.2.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // New group id: 3.5.7 onward is published as com.auroraoss. Not a version bump — this is
    // what forced Kotlin 2.3 and OkHttp 5 above, and it renamed half the surface we touch.
    // serialization-json is a runtime-only dependency of the library, so it has to be named
    // here to be on our compile classpath: the Play session is persisted with the library's
    // own serialiser now, not Gson.
    implementation("com.auroraoss:gplayapi:3.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Also runtime-only in the AAR pom. Needed at COMPILE time to walk the search response
    // ourselves — see PlayRepository.search. Same version the library resolves, so nothing
    // new is packaged.
    implementation("com.google.protobuf:protobuf-javalite:4.34.1")
    implementation("com.google.code.gson:gson:2.11.0")
    // OkHttp 5: gplayapi 3.6.x depends on 5.3.2, and Gradle would resolve to it anyway, so
    // pin it and keep the interceptor on the same line. Retrofit 3 is the release built
    // against the Kotlin-era OkHttp; it keeps binary compatibility with 2.x.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.github.g00fy2:versioncompare:1.5.0")
    implementation("io.insert-koin:koin-android:3.5.6")
    implementation("io.insert-koin:koin-androidx-compose:3.5.6")
    implementation("org.jsoup:jsoup:1.18.3")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.8")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")

}
