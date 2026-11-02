plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.tanuki"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.tanuki"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-dev"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("probe")
    flavorDimensions += "execution"
    productFlavors {
        create("modern") { dimension = "execution"; targetSdk = 34; applicationIdSuffix = ".modern" }
        create("compat") { dimension = "execution"; targetSdk = 28 }
    }
    buildTypes {
        debug { isDebuggable = true }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            // 仅显式请求时使用开发证书；正式发布仍需独立签名。
            if (providers.gradleProperty("developmentReleaseSigning").getOrElse("false") == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        create("probe") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    lint { abortOnError = true }
}
val noticesDirectory = layout.buildDirectory.dir("generated/notices")
val syncNotices = tasks.register<Copy>("syncNotices") {
    from(rootProject.file("THIRD_PARTY_NOTICES"))
    from(rootProject.file("LICENSE")) { into("licenses"); rename { "Tanuki-GPL-3.0.txt" } }
    from(rootProject.file("licenses/android-runtime-notices")) { into("licenses/android"); exclude("**/*.pom") }
    from(rootProject.file("licenses/ANDROID-DEPENDENCIES.md")) { into("licenses") }
    into(noticesDirectory)
}
android.sourceSets.getByName("main").assets.srcDir(noticesDirectory)
val nativeRuntime = tasks.register<Exec>("buildNativeRuntime") {
    workingDir(rootProject.projectDir)
    commandLine("bash", "runtime/build-native-runtime.sh")
    inputs.files(rootProject.file("runtime/build-native-runtime.sh"),rootProject.file("runtime/patches/proot-exitkill.patch"),file("src/main/cpp/supervisor.c"))
    outputs.dir(file("src/main/jniLibs/arm64-v8a"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncNotices,nativeRuntime) }
dependencies {
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
