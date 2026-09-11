package com.example.ami.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ami_settings")

class AmiPreferences(private val context: Context) {

    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_COMPLETE] ?: false }

    /**
     * The ordered escalation chain.
     *
     * Falls back to the single address saved by earlier versions, so upgrading never
     * leaves a configured user with nobody to escalate to.
     */
    val caregivers: Flow<List<CaregiverContact>> = context.dataStore.data.map { prefs ->
        CaregiverContacts.decode(
            raw = prefs[CAREGIVERS] ?: "",
            legacySingleEmail = prefs[CAREGIVER_EMAIL] ?: ""
        )
    }

    /** The user's own number in E.164 form, rung when an in-app check-in goes unanswered. */
    val userPhoneNumber: Flow<String> = context.dataStore.data.map { it[USER_PHONE] ?: "" }

    /** Whether the caregiver chain gets a weekly adherence summary. */
    val weeklySummaryEnabled: Flow<Boolean> = context.dataStore.data.map { it[WEEKLY_SUMMARY] ?: true }

    /**
     * What the person wants to be called. Empty until they say.
     *
     * Local only - this app has no accounts and no server-side user, so a "profile" here
     * is two fields on this device and nothing else. Kept deliberately small: a care app
     * should not collect what it has no use for.
     */
    val displayName: Flow<String> = context.dataStore.data.map { it[DISPLAY_NAME] ?: "" }

    /** Date of birth as an epoch day, or null if not given. Optional, and stays optional. */
    val dateOfBirth: Flow<Long?> = context.dataStore.data.map { it[DATE_OF_BIRTH] }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { it[ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setCaregivers(contacts: List<CaregiverContact>) {
        context.dataStore.edit { prefs ->
            prefs[CAREGIVERS] = CaregiverContacts.encode(contacts)
            // Kept in step so a downgrade, or any code still reading the old key,
            // still finds the primary contact rather than an empty string.
            prefs[CAREGIVER_EMAIL] = contacts.firstOrNull()?.email ?: ""
        }
    }

    suspend fun setUserPhoneNumber(number: String) {
        context.dataStore.edit { it[USER_PHONE] = number }
    }

    suspend fun setWeeklySummaryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[WEEKLY_SUMMARY] = enabled }
    }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { it[DISPLAY_NAME] = name.trim() }
    }

    /** Pass null to clear a date that was entered by mistake. */
    suspend fun setDateOfBirth(epochDay: Long?) {
        context.dataStore.edit { prefs ->
            if (epochDay == null) prefs.remove(DATE_OF_BIRTH) else prefs[DATE_OF_BIRTH] = epochDay
        }
    }

    suspend fun getDisplayNameOnce(): String = displayName.first()

    suspend fun getDateOfBirthOnce(): Long? = dateOfBirth.first()

    /**
     * Wipes every preference this app has written.
     *
     * Paired with clearing the database, this is the whole of "delete my data" - there is
     * no server copy to ask about, because nothing here is ever uploaded except a
     * caregiver summary the user asked to send.
     */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    /** Epoch milli of the last weekly summary that was actually sent, 0 if never. */
    suspend fun getLastSummarySentAt(): Long = context.dataStore.data.first()[LAST_SUMMARY_AT] ?: 0L

    suspend fun setLastSummarySentAt(epochMilli: Long) {
        context.dataStore.edit { it[LAST_SUMMARY_AT] = epochMilli }
    }

    suspend fun getCaregiversOnce(): List<CaregiverContact> = caregivers.first()

    suspend fun getUserPhoneNumberOnce(): String = userPhoneNumber.first()

    suspend fun isWeeklySummaryEnabledOnce(): Boolean = weeklySummaryEnabled.first()

    suspend fun isOnboardingCompleteOnce(): Boolean = onboardingComplete.first()

    companion object {
        private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

        /** Pre-chain single address. Still written, only read as a fallback. */
        private val CAREGIVER_EMAIL = stringPreferencesKey("caregiver_email")
        private val CAREGIVERS = stringPreferencesKey("caregiver_contacts")
        private val USER_PHONE = stringPreferencesKey("user_phone_number")
        private val WEEKLY_SUMMARY = booleanPreferencesKey("weekly_summary_enabled")
        private val LAST_SUMMARY_AT = longPreferencesKey("last_summary_sent_at")
        private val DISPLAY_NAME = stringPreferencesKey("display_name")
        private val DATE_OF_BIRTH = longPreferencesKey("date_of_birth")
    }
}
