package com.example.ami.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One step AMI took, or declined to take, while guiding a spoken goal.
 *
 * Kept for the caregiver, who otherwise has no way to see what the assistant has been
 * doing on the phone it is driving. Deliberately stores the *action* and what AMI
 * said - never the screen text that was sent to the model, which is the sensitive part
 * and has no business being retained on disk.
 */
@Entity(tableName = "action_log")
data class ActionLogRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The goal the user spoke, e.g. "video call my daughter". */
    val goal: String,
    /** The planner's chosen action: a label to point at, or a LAUNCH:/ACTION:/SETTINGS: verb. */
    val action: String,
    /** What AMI said aloud at this step, if anything. */
    val spokenText: String? = null,
    /** Foreground app at the time, so a caregiver can see where AMI was working. */
    val packageName: String? = null,
    val outcome: String = OUTCOME_OK,
    val timestampEpochMilli: Long = System.currentTimeMillis()
) {
    companion object {
        const val OUTCOME_OK = "OK"

        /** The planner named something that wasn't on screen, even after scrolling. */
        const val OUTCOME_NOT_FOUND = "NOT_FOUND"

        /** The goal finished. */
        const val OUTCOME_GOAL_REACHED = "GOAL_REACHED"

        /** The user stopped it, or AMI stopped itself. */
        const val OUTCOME_STOPPED = "STOPPED"

        /** Refused to read the screen because the foreground app is sensitive. */
        const val OUTCOME_BLOCKED = "BLOCKED"
    }
}
