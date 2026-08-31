plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.r1lockscreen"; compileSdk = 34
    defaultConfig { applicationId = "com.r1lockscreen"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // Xposed API is provided by the framework at runtime — compile against it only.
    compileOnly(files("libs/api-82.jar"))
}
