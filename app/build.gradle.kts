plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
// 只有用户确认发布版本后才传入这两个参数；日常验证仍使用开发版，不隐式发布。
val requestedVersion = providers.gradleProperty("tanukiVersionName").orNull
val requestedCode = providers.gradleProperty("tanukiVersionCode").orNull
require((requestedVersion == null) == (requestedCode == null)) { "发布版本名称与递增版本代码必须同时提供" }
require(requestedVersion == null || requestedVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"))) { "发布版本名称格式无效" }
val configuredCode = requestedCode?.toIntOrNull()?.also { require(it in 1..2_100_000_000) { "发布版本代码超出范围" } }
require(requestedCode == null || configuredCode != null) { "发布版本代码必须是正整数" }
android {
    namespace = "com.tanuki"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tanuki"
        minSdk = 26
        targetSdk = 34
        versionCode = configuredCode ?: 5
        versionName = requestedVersion ?: "0.1.3"
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
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
val noticesDirectory = layout.buildDirectory.dir("generated/notices")
val syncNotices = tasks.register<Sync>("syncNotices") {
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
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
