// Kotlin 2.3 needs R8 8.13.19 to read its class metadata — the one bundled with AGP 8.6.1 is
// older and logs "An error occurred when parsing kotlin metadata" for every Kotlin class it
// shrinks, then drops the metadata it could not read. The documented answer short of moving
// to AGP 8.13 is to put the newer R8 on the build classpath, from Google's own R8 repository
// (it is not on Maven Central). This block has to come before plugins {}.
buildscript {
    repositories {
        maven("https://storage.googleapis.com/r8-releases/raw")
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools:r8:8.13.19")
    }
}

plugins {
    id("com.android.application") version "8.6.1" apply false
    // 2.3.21, not because we need a newer language: gplayapi 3.6.x is compiled with it, and a
    // 2.1 compiler cannot read 2.3 metadata. KGP 2.3.21 supports Gradle 7.6.3-9.3 and AGP
    // 8.2.2-9.0, so neither of those has to move. The compose plugin must match the compiler.
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
