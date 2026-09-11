package com.example.ami.wellbeing

import android.content.Context
import com.example.ami.data.AmiDatabase
import com.example.ami.data.WellbeingEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Reads the wellbeing history and hands back the two shapes the screen needs: the
 * headline [WellbeingStory] and one [TrendSeries] per tab.
 *
 * Both loads pull a single span wide enough for the longest comparison the screen makes
 * (a year of trend, or this month against last month) and slice it in memory, rather
 * than issuing a query per card.
 */
class WellbeingRepository(context: Context) {

    private val dao = AmiDatabase.getInstance(context).wellbeingDao()

    /** Widest span any figure on the screen reaches back to. */
    private val historyDays = maxOf(TrendRange.YEAR.dayCount, MONTH_COMPARISON_DAYS)

    suspend fun story(today: LocalDate = LocalDate.now()): WellbeingStory = withContext(Dispatchers.IO) {
        WellbeingStory.of(history(today), today)
    }

    suspend fun series(
        dimension: WellbeingDimension,
        range: TrendRange,
        today: LocalDate = LocalDate.now()
    ): TrendSeries = withContext(Dispatchers.IO) {
        TrendSeries.of(history(today), dimension, range, today)
    }

    /** Both shapes at once, so opening the screen is one query rather than two. */
    suspend fun load(
        dimension: WellbeingDimension,
        range: TrendRange,
        today: LocalDate = LocalDate.now()
    ): Pair<WellbeingStory, TrendSeries> = withContext(Dispatchers.IO) {
        val entries = history(today)
        WellbeingStory.of(entries, today) to TrendSeries.of(entries, dimension, range, today)
    }

    /**
     * Records or corrects one day's reading.
     *
     * Public because nothing else in the app writes these rows yet - this is the seam a
     * daily check-in question, or a Health Connect sleep import, plugs into.
     */
    suspend fun record(
        day: LocalDate,
        stress: Int,
        sleep: Int,
        social: Int,
        connections: Int = 0
    ) = withContext(Dispatchers.IO) {
        dao.upsert(
            WellbeingEntry(
                epochDay = day.toEpochDay(),
                stress = WellbeingEntry.clamp(stress),
                sleep = WellbeingEntry.clamp(sleep),
                social = WellbeingEntry.clamp(social),
                connections = connections.coerceAtLeast(0)
            )
        )
    }

    suspend fun hasAnyHistory(): Boolean = withContext(Dispatchers.IO) { dao.count() > 0 }

    private suspend fun history(today: LocalDate): List<WellbeingEntry> =
        dao.between(today.minusDays(historyDays.toLong()).toEpochDay(), today.toEpochDay())

    private companion object {
        /** This month against last month needs two months of rows. */
        const val MONTH_COMPARISON_DAYS = 60
    }
}
