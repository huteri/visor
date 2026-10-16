plugins {
    id("visor.android.library")
}

android {
    namespace = "com.macedonga.visor.core.assistant"
}

dependencies {
    api(project(":core:connection"))
    // The photo for a visual question comes over airship.
    implementation(project(":core:media"))
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    // Android's org.json is a stub on the host JVM.
    testImplementation(libs.json.test)

}
