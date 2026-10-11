import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 正式包签名喵。口令写在仓库根的 keystore.properties 里（本地自用签名，不是分发凭据）喵；
// 文件不存在时退化成未签名包，这样别人 clone 下来也能直接构建喵。
val keystoreProps = Properties()
val keystoreFile = rootProject.file("keystore.properties")
if (keystoreFile.exists()) {
    keystoreFile.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "com.qingyu.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qingyu.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.1.2"
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    // 解决 'ui-test-* is not used' 这个警告喵
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.gson)

    // Compose 核心图标库，提供 Menu、Search、MoreVert 等基础图标喵
    implementation("androidx.compose.material:material-icons-core")

    // Compose 扩展图标库，提供 AddPhotoAlternate、FavoriteBorder、VolumeOff 等高级与 AutoMirrored 图标喵
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation(libs.junit)
    // Android 单元测试里的 org.json 是空壳，调用会抛 "not mocked" 喵，
    // 而协议解析正是靠它做的，所以测试类路径上需要一份真实实现喵。
    testImplementation(libs.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
