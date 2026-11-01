package app.nuviowatch.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonsTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun easterDates() {
        assertEquals(d("2025-04-20"), Seasons.easterSunday(2025))
        assertEquals(d("2026-04-05"), Seasons.easterSunday(2026))
        assertEquals(d("2027-03-28"), Seasons.easterSunday(2027))
    }

    @Test
    fun themesByDate() {
        assertEquals(Season.HALLOWEEN, Seasons.forDate(d("2026-10-01")))
        assertEquals(Season.HALLOWEEN, Seasons.forDate(d("2026-10-31")))
        assertEquals(Season.CHRISTMAS, Seasons.forDate(d("2026-11-01")))
        assertEquals(Season.CHRISTMAS, Seasons.forDate(d("2026-12-31")))
        assertEquals(Season.NEW_YEAR, Seasons.forDate(d("2027-01-01")))
        assertEquals(Season.NEW_YEAR, Seasons.forDate(d("2027-01-07")))
        assertEquals(Season.EVERYDAY, Seasons.forDate(d("2027-01-08")))
        assertEquals(Season.EVERYDAY, Seasons.forDate(d("2027-02-14")))
        assertEquals(Season.EASTER, Seasons.forDate(d("2026-03-29"))) // Palm Sunday
        assertEquals(Season.EASTER, Seasons.forDate(d("2026-04-06"))) // Easter Monday
        assertEquals(Season.SPRING, Seasons.forDate(d("2026-04-07")))
        assertEquals(Season.SPRING, Seasons.forDate(d("2026-05-31")))
        assertEquals(Season.SUMMER, Seasons.forDate(d("2026-07-15")))
        assertEquals(Season.AUTUMN, Seasons.forDate(d("2026-09-10")))
    }

    @Test
    fun eidFromTheIslamicCalendar() {
        val fitr = LocalDate.from(java.time.chrono.HijrahDate.of(1447, 10, 1))
        assertEquals(Season.EID, Seasons.forDate(fitr))
        assertEquals(Season.EID, Seasons.forDate(fitr.plusDays(2)))
        assertEquals(Season.SPRING, Seasons.forDate(fitr.plusDays(3))) // late March 2026
        val adha = LocalDate.from(java.time.chrono.HijrahDate.of(1447, 12, 10))
        assertEquals(Season.EID, Seasons.forDate(adha))
    }

    @Test
    fun christmasCountdown() {
        assertEquals(54, Seasons.daysToChristmas(d("2026-11-01")))
        assertEquals(0, Seasons.daysToChristmas(d("2026-12-25")))
    }
}
