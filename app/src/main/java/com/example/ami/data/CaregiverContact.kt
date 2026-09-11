package com.example.ami.data

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

/**
 * One person AMI can notify, in the order they should be reached.
 *
 * Order is the whole point: the first entry is the person who handles the day-to-day,
 * and later entries exist for when that person isn't responding either. Everyone being
 * emailed about every missed dose trains all of them to ignore the emails.
 */
data class CaregiverContact(
    val name: String,
    val email: String
) {
    val displayName: String get() = name.ifBlank { email }
}

object CaregiverContacts {

    private val gson = Gson()
    private val listType = object : TypeToken<List<CaregiverContact>>() {}.type

    /** Same shape as the server's check, so a saved address can't be rejected later. */
    private val EMAIL = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")

    fun isValidEmail(email: String): Boolean = EMAIL.matches(email.trim())

    fun encode(contacts: List<CaregiverContact>): String = gson.toJson(contacts)

    /**
     * Tolerant by design. A corrupted preference must not leave a user with no
     * caregiver configured and no indication why - it degrades to "none configured",
     * which the UI already handles, rather than throwing inside a check-in.
     *
     * [legacySingleEmail] carries forward the single address saved by earlier versions
     * so upgrading doesn't silently drop the only contact.
     */
    fun decode(raw: String, legacySingleEmail: String = ""): List<CaregiverContact> {
        val stored = if (raw.isBlank()) {
            emptyList()
        } else {
            try {
                gson.fromJson<List<CaregiverContact>>(raw, listType).orEmpty()
            } catch (e: JsonSyntaxException) {
                emptyList()
            }
        }

        val cleaned = stored
            .filterNotNull()
            .map { CaregiverContact(it.name.orEmpty().trim(), it.email.orEmpty().trim()) }
            .filter { isValidEmail(it.email) }
            .distinctBy { it.email.lowercase() }

        if (cleaned.isNotEmpty()) return cleaned

        val legacy = legacySingleEmail.trim()
        return if (isValidEmail(legacy)) listOf(CaregiverContact("", legacy)) else emptyList()
    }
}
