// Чистый Kotlin: без java.* и Android, чтобы позже перевести модуль в KMP.
// kotlinx.serialization — мультиплатформенная, для формата пака карты.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
