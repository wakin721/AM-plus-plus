package dev.amenhancer.module.usb

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import dev.amenhancer.module.ModuleConstants

/**
 * Empty provider used only to create a narrow URI grant from AM++ to Apple Music.
 *
 * Android 11+ package visibility can otherwise hide AM++ from the code injected
 * into Apple Music, causing an explicit bindService() to the USB broker to
 * return false. Granting Apple Music read access to one URI from this provider
 * makes the provider-owning AM++ package visible without exposing app data.
 */
class UsbDirectVisibilityProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

internal object UsbDirectVisibilityGrant {
    private const val AUTHORITY_SUFFIX = ".usb-direct-visibility"
    private const val BRIDGE_PATH = "bridge"

    fun grantToAppleMusic(context: Context): Boolean {
        val application = context.applicationContext
        val uri = Uri.Builder()
            .scheme("content")
            .authority(application.packageName + AUTHORITY_SUFFIX)
            .appendPath(BRIDGE_PATH)
            .build()
        return runCatching {
            application.grantUriPermission(
                ModuleConstants.TARGET_PACKAGE,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        }.getOrDefault(false)
    }
}
