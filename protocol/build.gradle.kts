plugins { id("com.android.library") }
android {
    namespace = "com.shixi.protocol"; compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = false }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // The Android SDK only ships a stubbed org.json; a real implementation is required
    // to exercise the response parsers on the JVM (no device needed).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
