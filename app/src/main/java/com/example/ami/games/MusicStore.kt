package com.example.ami.games

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit

/**
 * The music the person has added, kept per mood.
 *
 * The design's "ADD MUSIC +" is the answer to this screen having no audio: rather than
 * shipping tracks, it lets someone point at music already on their phone. That is better
 * than a bundled library anyway - the songs that settle an eighty-year-old are the ones
 * they already know, not whatever a stock library considers calming.
 *
 * URIs are stored with a **persistable read grant** taken at pick time. Without that the
 * permission dies with the process and every track silently fails to open the next
 * morning, which is exactly the sort of fault nobody reports and nobody can reproduce.
 *
 * Stored in SharedPreferences rather than Room: this is a short list of strings the user
 * can rebuild in seconds, and it has no relationship to anything else in the database.
 */
class MusicStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ami_music", Context.MODE_PRIVATE)

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    data class Track(val uri: Uri, val title: String)

    fun tracksFor(mood: MusicLibrary.Mood): List<Track> =
        prefs.getStringSet(key(mood), emptySet())
            .orEmpty()
            .sorted()
            .mapNotNull { encoded ->
                // "<title><uri>" - a unit separator, which cannot occur in either half.
                val parts = encoded.split(SEPARATOR, limit = 2)
                if (parts.size != 2) null else Track(Uri.parse(parts[1]), parts[0])
            }

    /**
     * @return how many were actually added; duplicates and unreadable picks are skipped.
     */
    fun add(mood: MusicLibrary.Mood, uris: List<Uri>): Int {
        val existing = prefs.getStringSet(key(mood), emptySet()).orEmpty().toMutableSet()
        var added = 0

        uris.forEach { uri ->
            val granted = takePersistablePermission(uri)
            if (!granted) return@forEach
            val title = displayName(uri) ?: return@forEach
            if (existing.add("$title$SEPARATOR$uri")) added++
        }

        prefs.edit { putStringSet(key(mood), existing) }
        return added
    }

    fun clear(mood: MusicLibrary.Mood) {
        prefs.edit { remove(key(mood)) }
    }

    /**
     * Without this the grant lasts only until the process dies, and the track stops
     * opening with a bare SecurityException days later.
     */
    private fun takePersistablePermission(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "No persistable grant for $uri", e)
        false
    }

    /** The file's own name, tidied of its extension. Null when the pick is unreadable. */
    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read a name for $uri", e)
        null
    }

    private fun key(mood: MusicLibrary.Mood) = "tracks_${mood.name}"

    private companion object {
        const val TAG = "AMI_MUSIC"
        const val SEPARATOR = ""
    }
}
