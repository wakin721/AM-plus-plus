package io.github.proify.lyricon.amprovider.xposed

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicDexKitResolverApiCompatibilityTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `cache key supports package info before api 28`() {
        val resolver = projectFile(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMusicDexKitResolver.kt",
        )

        assertTrue(resolver.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P"))
        assertTrue(resolver.contains("packageInfo.versionCode.toLong()"))
    }
}
