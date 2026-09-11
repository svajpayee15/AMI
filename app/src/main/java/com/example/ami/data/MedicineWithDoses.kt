package com.example.ami.data

import androidx.room.Embedded
import androidx.room.Relation

/** A medicine together with every time of day it is due. */
data class MedicineWithDoses(
    @Embedded val medicine: Medicine,
    @Relation(parentColumn = "id", entityColumn = "medicineId")
    val doses: List<MedicineDose>
) {
    /** Doses in clock order, which is the order a day is actually lived in. */
    val orderedDoses: List<MedicineDose> get() = doses.sortedBy { it.time }
}
