plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
android { namespace = "com.shixi.client"; compileSdk = 37
    defaultConfig { applicationId = "com.shixi.client"; minSdk = 26; targetSdk = 37; versionCode = 5; versionName = "0.2.0"; ndk { abiFilters += "arm64-v8a" }; testInstrumentationRunner = providers.gradleProperty("shixi.testRunner").getOrElse("com.shixi.client.AccountVerification") }
    // Distribution signing is explicit; never silently fall back to a debug key.
    val signingNames = listOf("SHIXI_RELEASE_STORE_FILE", "SHIXI_RELEASE_STORE_PASSWORD", "SHIXI_RELEASE_KEY_ALIAS", "SHIXI_RELEASE_KEY_PASSWORD")
    val signingValues = signingNames.map { providers.environmentVariable(it).orNull }
    require(signingValues.all { it == null } || signingValues.all { !it.isNullOrBlank() }) { "Provide all four SHIXI_RELEASE signing variables, or none for an unsigned release." }
    if (signingValues.all { !it.isNullOrBlank() }) {
        signingConfigs.create("distribution") {
            storeFile = file(signingValues[0]!!)
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("distribution")
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}
dependencies {
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation(project(":protocol"))
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    // HLS (radio + live rooms) lives in a separate artifact.
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
