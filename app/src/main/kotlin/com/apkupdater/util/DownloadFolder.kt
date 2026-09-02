package com.apkupdater.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Where the Download button puts the file.
 *
 * The default is Downloads/APKUpdater, reached through MediaStore on Q+ and a plain path below
 * it. The user can point us somewhere else with the system folder picker; we then hold a
 * persisted grant on that tree and write through the Storage Access Framework.
 *
 * Two things here are not obvious, and both would otherwise be silent bugs:
 *
 * 1. **A chosen folder can stop working.** The card is pulled, the folder is deleted, the grant
 *    is revoked from system settings, or the app is reinstalled. Every write therefore checks
 *    the grant, and on failure falls back to Downloads *and says so* — a file that quietly
 *    lands somewhere other than where the user was told is worse than no file at all.
 *
 * 2. **SAF providers may rewrite the file name to match the MIME type they were handed.** A
 *    provider is free to strip an extension that does not match the MIME and append the one
 *    it derives from it — a provider mapping `application/octet-stream` to `bin` would hand
 *    "App-1.0.xapk" back as "App-1.0.xapk.bin" (the stock one since Android 7 special-cases
 *    octet-stream and keeps the name; older and third-party ones are not promised to). We read
 *    the name back after creating the document, rename it if an extension was appended, and
 *    report whatever it truly ended up being called. Same trap that once turned .xapk into
 *    .xapk.zip on the MediaStore path.
 */
object DownloadFolder {

    private const val TAG = "DownloadFolder"
    // Not a const: `or` is a function call, which a const initializer may not contain.
    private val GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** The name the file really got, and whether we had to fall back to Downloads. */
    data class Saved(val fileName: String, val usedFallback: Boolean)

    /**
     * True while we still hold a write grant on this tree. The grant survives reboots, but not
     * the user revoking it, storage being unmounted, or the providing app going away.
     */
    fun isUsable(context: Context, treeUri: String): Boolean {
        val uri = parse(treeUri) ?: return false
        return runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
        }.getOrDefault(false)
    }

    /** "Download/APK", "Documents/APK" or "1A2B-3C4D/APK" — what to show under the setting. */
    fun label(context: Context, treeUri: String): String? {
        val uri = parse(treeUri) ?: return null
        return runCatching {
            // The stock provider's tree ids are "<root>:<path>": "primary:Download/APK",
            // "home:APK" for anything under Documents (that root is named "home"), and
            // "1A2B-3C4D:APK" on a card. An id without a colon belongs to another provider
            // and is opaque; its display name is the best we can show for it.
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (!docId.contains(':')) {
                return@runCatching displayName(
                    context, DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                ) ?: docId
            }
            val root = docId.substringBefore(':')
            val path = docId.substringAfter(':')
            val prefix = when {
                root.equals("primary", true) -> ""
                root.equals("home", true) -> "Documents/"
                else -> "$root/"
            }
            (prefix + path).trim('/').ifEmpty { "/" }
        }.getOrNull()
    }

    /**
     * Takes a persistable grant on the picked tree and drops the previous one, so grants do not
     * pile up towards the per-app limit. Returns false if the picker handed back something we
     * cannot hold on to, in which case the caller must not store it as the folder.
     */
    fun remember(context: Context, previous: String, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, GRANT)
        if (previous.isNotEmpty() && previous != uri.toString()) release(context, previous)
        true
    }.getOrElse {
        Log.e(TAG, "Could not persist folder grant", it)
        false
    }

    /** Gives the grant back. Best effort: a stale grant is harmless, a crash here is not. */
    fun release(context: Context, treeUri: String) {
        val uri = parse(treeUri) ?: return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, GRANT) }
            .onFailure { Log.e(TAG, "Could not release folder grant", it) }
    }

    /**
     * Writes [file] out under [fileName]: into the chosen folder when there is a usable one,
     * into Downloads/APKUpdater otherwise.
     */
    fun save(context: Context, treeUri: String, file: File, fileName: String): Saved {
        val tree = parse(treeUri)
        if (tree != null && isUsable(context, treeUri)) {
            val name = runCatching { saveToTree(context, tree, file, fileName) }
                .onFailure { Log.e(TAG, "Write to the chosen folder failed", it) }
                .getOrNull()
            if (name != null) return Saved(name, usedFallback = false)
        }
        val name = saveToDownloads(context, file, fileName)
        // Only a fallback if the user had actually asked for somewhere else — the pref being
        // set is the honest test, not whether the Uri in it survived parsing.
        return Saved(name, usedFallback = treeUri.isNotEmpty())
    }

    private fun saveToTree(context: Context, tree: Uri, file: File, fileName: String): String {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val target = DocumentsContract.createDocument(resolver, parent, mimeFor(fileName), fileName)
            ?: throw IOException("Could not create $fileName in the chosen folder")

        // A failed write must not leave a 0-byte or half-written file behind: the user would
        // find a stub that does not install next to the good copy in Downloads, and the next
        // attempt would be deduped to "(1)" because the stub holds the name.
        try {
            resolver.openOutputStream(target)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("Could not open $fileName for writing")
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw t
        }

        // See the note on the class: the provider may have appended an extension of its own.
        // Only an APPENDED one is undone — "App.xapk.bin" back to "App.xapk". A name the
        // provider changed for another reason, such as "App (1).apk" because "App.apk" is
        // already there, is left alone and reported as it is: renaming it over the existing
        // file is exactly what we must not do.
        val actual = displayName(context, target) ?: fileName
        if (actual == fileName || !actual.startsWith(fileName)) return actual
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, target, fileName) }
            .onFailure { Log.e(TAG, "Could not rename $actual back to $fileName", it) }
            .getOrNull()
        return renamed?.let { displayName(context, it) } ?: actual
    }

    /**
     * The stock provider round-trips a .apk name unchanged when given the package-archive type.
     * Everything else we save (.xapk, .apks) has no registered type, and octet-stream is the
     * honest answer for it. The stock provider since Android 7 special-cases octet-stream and
     * keeps the name; any provider that appends one instead is undone by the rename in
     * [saveToTree].
     */
    private fun mimeFor(fileName: String) =
        if (fileName.endsWith(".apk", true)) "application/vnd.android.package-archive"
        else "application/octet-stream"

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Returns the name the file was given: MediaStore renames a duplicate to "name (1).apk". */
    private fun saveToDownloads(context: Context, file: File, fileName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                // octet-stream for both .apk and .xapk, so MediaStore does not append an
                // extension of its own (.xapk used to end up saved as .xapk.zip).
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + "APKUpdater"
                )
            }
            val resolver = context.contentResolver
            // Both nulls used to be swallowed, and the user was told "Saved" with nothing
            // written. Throwing turns that into the download_failed message it deserves.
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused to create $fileName")
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open $fileName for writing")
            return runCatching {
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: fileName
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, "APKUpdater").apply { if (!exists()) mkdirs() }
            file.copyTo(File(targetDir, fileName), overwrite = true)
            return fileName
        }
    }

    private fun parse(treeUri: String): Uri? =
        if (treeUri.isEmpty()) null else runCatching { Uri.parse(treeUri) }.getOrNull()
}
