plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
}

// Coordinates for the published library. The detached sample imports these
// (com.syncdb:synccore, com.syncdb:synctransport-ktor).
allprojects {
    group = "com.syncdb"
    version = "0.1.0"
}
