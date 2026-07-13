plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loanagent.devicecontroller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loanagent.devicecontroller"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

tasks.withType<Test>().configureEach {
    val robolectricTemp = layout.buildDirectory.dir("robolectric-tmp")
    doFirst {
        robolectricTemp.get().asFile.mkdirs()
    }
    systemProperty("java.io.tmpdir", robolectricTemp.get().asFile.absolutePath)
}
