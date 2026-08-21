package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressiveSettingsUiStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `actual settings entry points render the expressive compose theme`() {
        val settings = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt",
        )
        val usb = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/UsbBitPerfectSettingsActivity.kt",
        )
        val theme = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/theme/AmppExpressiveTheme.kt",
        )

        assertTrue(settings.contains("setContent {"))
        assertTrue(settings.contains("AmppExpressiveTheme {"))
        assertTrue(settings.contains("AmppSettingsScreen("))
        assertFalse(settings.contains("setContentView(buildScreen()"))
        assertTrue(usb.contains("setContent {"))
        assertTrue(usb.contains("AmppExpressiveTheme {"))
        assertTrue(usb.contains("UsbAudioSettingsScreen("))
        assertFalse(usb.contains("setContentView(buildScreen()"))
        assertTrue(theme.contains("MaterialExpressiveTheme("))
        assertTrue(theme.contains("MotionScheme.expressive()"))
    }

    @Test
    fun `compose surface exposes expressive controls and integrated USB navigation`() {
        val ui = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/ExpressiveSettingsUi.kt",
        )

        assertTrue(ui.contains("CenterAlignedTopAppBar("))
        assertTrue(ui.contains("RoundedCornerShape(32.dp)"))
        assertTrue(ui.contains("ExpressiveSwitchRow("))
        assertTrue(ui.contains("Slider("))
        assertTrue(ui.contains("title = \"USB 音频输出\""))
        assertTrue(ui.contains("CustomLyricsPage("))
        assertTrue(ui.contains("UsbAudioSettingsScreen("))
    }

    @Test
    fun `active settings dialogs use the expressive compose host`() {
        val activity = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt",
        )
        val screen = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/ExpressiveSettingsUi.kt",
        )
        val dialogs = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/ExpressiveSettingsDialogs.kt",
        )

        assertTrue(screen.contains("ExpressiveSettingsDialogHost(dialogState, dialogActions)"))
        assertTrue(activity.contains("showTargetLanguage = ::showTargetLanguagePickerExpressive"))
        assertTrue(activity.contains("addCustomLyrics = { showCustomLyricsEditorExpressive() }"))
        assertTrue(activity.contains("syncCustomLyrics = ::syncCustomLyricsFromGitHubExpressive"))
        assertTrue(dialogs.contains("ExpressiveSettingsDialogHost("))
        assertTrue(dialogs.contains("AlertDialog("))
        assertTrue(dialogs.contains("shape = RoundedCornerShape(32.dp)"))
        assertTrue(dialogs.contains("shape = RoundedCornerShape(36.dp)"))
        assertFalse(dialogs.contains("android.app.AlertDialog"))
    }
}
