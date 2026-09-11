package com.example.ami.navigation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.util.Locale

/**
 * Maps a spoken request onto a direct Settings intent.
 *
 * Walking the Settings tree screen-by-screen is where users get lost: the hierarchy is
 * deep, the wording differs per manufacturer, and the target is usually below the fold.
 * Jumping straight to the right page sidesteps all of that, so the planner is taught to
 * prefer `SETTINGS:<key>` over pointing at "Settings" and navigating from there.
 *
 * Some of these actions only exist on newer platforms, and OEM skins occasionally drop
 * one. That is handled the same way in every case: if the intent won't start, fall back
 * to the top-level Settings screen rather than failing silently.
 */
object SettingsDeepLinks {

    private const val TAG = "AMI_SETTINGS_LINK"

    data class Destination(
        val key: String,
        val action: String,
        /** Spoken back to the user, e.g. "the Wi-Fi settings". */
        val label: String,
        val phrases: List<String>
    )

    val destinations: List<Destination> = listOf(
        Destination("wifi", Settings.ACTION_WIFI_SETTINGS, "the Wi-Fi settings",
            listOf("wifi", "wi fi", "wireless", "internet", "network")),
        Destination("bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, "the Bluetooth settings",
            listOf("bluetooth", "headphones", "pair device", "earphones")),
        Destination("mobile_data", Settings.ACTION_DATA_ROAMING_SETTINGS, "the mobile data settings",
            listOf("mobile data", "cellular", "roaming", "sim", "data")),
        Destination("airplane", Settings.ACTION_AIRPLANE_MODE_SETTINGS, "the aeroplane mode settings",
            listOf("airplane", "aeroplane", "flight mode")),

        Destination("display", Settings.ACTION_DISPLAY_SETTINGS, "the display settings",
            listOf("display", "brightness", "screen", "text size", "font size",
                "bigger text", "make text bigger", "larger text", "screen timeout", "dark mode")),
        Destination("sound", Settings.ACTION_SOUND_SETTINGS, "the sound settings",
            listOf("sound", "volume", "ringtone", "louder", "silent", "vibrate", "mute")),

        Destination("accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, "the accessibility settings",
            listOf("accessibility", "magnification", "talkback", "screen reader",
                "hearing", "easier to see", "easier to use")),
        Destination("captions", Settings.ACTION_CAPTIONING_SETTINGS, "the caption settings",
            listOf("captions", "subtitles", "closed caption")),

        Destination("battery", Settings.ACTION_BATTERY_SAVER_SETTINGS, "the battery settings",
            listOf("battery", "power saving", "battery saver", "charge")),
        Destination("storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "the storage settings",
            listOf("storage", "space", "memory", "full", "free up space")),

        Destination("apps", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, "the apps settings",
            listOf("apps", "applications", "uninstall", "app info", "remove app")),
        Destination("app_details", Settings.ACTION_APPLICATION_SETTINGS, "the app settings",
            listOf("app settings", "application settings")),

        Destination("location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "the location settings",
            listOf("location", "gps", "maps location")),
        Destination("security", Settings.ACTION_SECURITY_SETTINGS, "the security settings",
            listOf("security", "lock screen", "pin", "password", "fingerprint", "face unlock")),
        Destination("privacy", Settings.ACTION_PRIVACY_SETTINGS, "the privacy settings",
            listOf("privacy", "permissions")),

        Destination("date_time", Settings.ACTION_DATE_SETTINGS, "the date and time settings",
            listOf("date", "time", "clock", "timezone", "time zone")),
        Destination("language", Settings.ACTION_LOCALE_SETTINGS, "the language settings",
            listOf("language", "locale", "translate", "change language")),
        Destination("keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS, "the keyboard settings",
            listOf("keyboard", "typing", "input method", "autocorrect")),

        Destination("accounts", Settings.ACTION_SYNC_SETTINGS, "the accounts settings",
            listOf("account", "accounts", "google account", "sync", "sign in")),
        Destination("add_account", Settings.ACTION_ADD_ACCOUNT, "the add-account screen",
            listOf("add account", "new account")),

        Destination("notifications", ACTION_ALL_NOTIFICATIONS, "the notification settings",
            listOf("notifications", "alerts", "badges", "popup")),
        Destination("device_info", Settings.ACTION_DEVICE_INFO_SETTINGS, "the about-phone screen",
            listOf("about phone", "phone info", "android version", "model number", "imei")),
        Destination("nfc", Settings.ACTION_NFC_SETTINGS, "the NFC settings",
            listOf("nfc", "tap to pay", "contactless")),
        Destination("print", Settings.ACTION_PRINT_SETTINGS, "the printing settings",
            listOf("print", "printer", "printing")),
        Destination("home", Settings.ACTION_HOME_SETTINGS, "the home-screen settings",
            listOf("home screen", "launcher", "default home")),

        Destination("settings", Settings.ACTION_SETTINGS, "the main settings screen",
            listOf("settings", "phone settings", "main settings"))
    )

    private val byKey: Map<String, Destination> = destinations.associateBy { it.key }

    fun byKey(key: String): Destination? = byKey[key.trim().lowercase(Locale.US)]

    /**
     * Best-effort phrase match, used when the planner names a destination in words
     * rather than by key. Longest phrase wins so "add account" beats "account".
     */
    fun byPhrase(spoken: String): Destination? {
        val text = spoken.lowercase(Locale.US)
        return destinations
            .flatMap { destination -> destination.phrases.map { it to destination } }
            .filter { (phrase, _) -> text.contains(phrase) }
            .maxByOrNull { (phrase, _) -> phrase.length }
            ?.second
    }

    /** Resolves by key first, then by phrase. */
    fun resolve(target: String): Destination? = byKey(target) ?: byPhrase(target)

    /**
     * Opens [target]. Returns the destination actually opened, or null if even the
     * top-level Settings screen could not be launched.
     *
     * [context] may be an AccessibilityService, so NEW_TASK is always required.
     */
    fun open(context: Context, target: String): Destination? {
        val destination = resolve(target)
        if (destination == null) {
            Log.w(TAG, "No settings destination matched '$target'")
            return openRoot(context)
        }
        return if (start(context, destination.action)) {
            destination
        } else {
            Log.w(TAG, "'${destination.action}' unavailable on this device, falling back to root")
            openRoot(context)
        }
    }

    private fun openRoot(context: Context): Destination? {
        val root = byKey["settings"] ?: return null
        return if (start(context, root.action)) root else null
    }

    private fun start(context: Context, action: String): Boolean {
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            // ActivityNotFoundException on OEM skins that drop an action, and
            // SecurityException on a handful of guarded screens.
            false
        }
    }

    /** Key list injected into the planner's system prompt so prompt and code stay in sync. */
    fun promptVocabulary(): String = destinations.joinToString(", ") { it.key }

    /**
     * Not a public Settings constant, but the documented action string that every
     * Android build ships. [start] falls back to the root screen if it is missing.
     */
    private const val ACTION_ALL_NOTIFICATIONS = "android.settings.NOTIFICATION_SETTINGS"
}
