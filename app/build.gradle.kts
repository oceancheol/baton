plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.baton.parking"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.baton.parking"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // M1 단계: 별도 키스토어 없이 debug 서명 설정을 그대로 쓴다. 배포용 서명은 이후 단계에서 교체.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG: 차량 상태 상세 로그를 debug 빌드에서만 남기는 데 쓴다.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // 뷰모델 단위 시험: android.jar 의 빈 껍데기(Application 등)가 예외 대신 기본값을 돌려주게 한다.
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                it.testLogging { events("passed", "failed", "skipped") }
            }
        }
    }
}

dependencies {
    implementation(project(":core"))

    // Pleos Connect 차량 SDK. 호출은 signal/PleosSignalSource.kt 한 파일에만 둔다.
    implementation("ai.pleos.playground:Vehicle:2.3.2")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 단위 시험 (:core 와 같은 kotlin.test + JUnit 5)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
