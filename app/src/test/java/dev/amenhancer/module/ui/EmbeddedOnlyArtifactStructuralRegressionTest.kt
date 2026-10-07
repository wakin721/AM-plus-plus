package dev.amenhancer.module.ui
import dev.amenhancer.module.hook.readRefactorComponent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSettingsArtifactStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    private fun projectPath(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::exists) ?: File(relativePath)

    @Test
    fun `artifact exposes settings only through the injected Apple Music entry`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val entry = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertFalse(manifest.contains("android:name=\".ui.SettingsActivity\""))
        assertFalse(manifest.contains("android:name=\".LauncherAlias\""))
        assertTrue(manifest.contains("android:name=\".usb.UsbDirectDeviceBrokerService\""))
        assertTrue(entry.contains("EmbeddedSettingsHost.install("))
        assertTrue(entry.contains("EmbeddedRuntimeSettingsController("))
        assertTrue(host.contains("Application.ActivityLifecycleCallbacks"))
        assertTrue(host.contains("showSettingsPage(activity)"))
    }

    @Test
    fun `configuration migration and file transaction sources are retained`() {
        listOf(
            "app/src/main/java/dev/amenhancer/module/ui/CurrentSongIdentityRequester.kt",
            "app/src/main/java/dev/amenhancer/module/ModuleApplication.kt",
            "app/src/main/java/dev/amenhancer/module/XposedServiceSnapshot.kt",
            "app/src/main/java/dev/amenhancer/module/config/ConfigStore.kt",
            "app/src/main/java/dev/amenhancer/module/font/SafFontImporter.kt",
            "app/src/main/java/dev/amenhancer/module/lyrics/CustomLyricsManager.kt",
        ).forEach { path -> assertTrue("standalone source missing: $path", projectPath(path).isFile) }
    }
}
