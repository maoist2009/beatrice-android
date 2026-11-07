// Plugin versions come from the local Gradle cache (same set the dreamlite
// and monocles_chat builds use); the app module applies the ids without
// versions so an offline build never needs the plugin marker artifacts.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.0.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")
    }
}
