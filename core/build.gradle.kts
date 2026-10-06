plugins { id("org.jetbrains.kotlin.multiplatform") }

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    sourceSets {
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies { compileOnly("org.json:json:20240303") }
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation("org.json:json:20240303")
        }
    }
}
tasks.register("test") { dependsOn("jvmTest") }
