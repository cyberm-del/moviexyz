plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.plugin")
}

android {
    namespace = "com.moviexyz.multisource"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 24
        targetSdk = 34
        
        plugin {
            name = "MultiSource Plugin"
            author = "moviexyz"
            version = "1.0.0"
            description = "Extension for MusicHQ, MovieMaze, KickassAnime, and Mirror sites"
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.lagradost.cloudstream3:cloudstream3:1.0.0")
    implementation("org.jsoup:jsoup:1.14.3")
}