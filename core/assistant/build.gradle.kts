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
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Android's org.json is a stub on the host JVM.
    testImplementation(libs.json.test)

}
