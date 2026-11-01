package app.nuviowatch.core

import java.time.LocalDate
import java.time.temporal.ChronoField

/** The app's look for the time of year. */
enum class Season { EVERYDAY, HALLOWEEN, CHRISTMAS, EASTER, EID }

/** Which seasonal theme is on for a date. Easter and Eid move every year, so they're worked out. */
object Seasons {
    fun forDate(date: LocalDate): Season = when {
        isEid(date) -> Season.EID
        isEaster(date) -> Season.EASTER
        date.monthValue == 10 -> Season.HALLOWEEN
        date.monthValue == 11 || date.monthValue == 12 -> Season.CHRISTMAS
        else -> Season.EVERYDAY
    }

    /** Easter Sunday (Western), by the anonymous Gregorian algorithm. */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** Palm Sunday to Easter Monday. */
    fun isEaster(date: LocalDate): Boolean {
        val easter = easterSunday(date.year)
        return !date.isBefore(easter.minusDays(7)) && !date.isAfter(easter.plusDays(1))
    }

    /**
     * Eid al-Fitr (1–3 Shawwal) and Eid al-Adha (10–13 Dhu al-Hijjah), from the Umm al-Qura
     * calendar. Local moon sighting can put the real day one day either side.
     */
    fun isEid(date: LocalDate): Boolean = runCatching {
        val h = java.time.chrono.HijrahDate.from(date)
        val month = h.get(ChronoField.MONTH_OF_YEAR)
        val day = h.get(ChronoField.DAY_OF_MONTH)
        (month == 10 && day in 1..3) || (month == 12 && day in 10..13)
    }.getOrDefault(false)

    fun daysToChristmas(date: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(date, LocalDate.of(date.year, 12, 25))
}
