package com.gabriel.gamedrop.data.releases

import com.gabriel.gamedrop.core.protectTranslationTerms
import com.gabriel.gamedrop.core.restoreTranslationTerms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ReleaseFeedUtilsTest {
    @Test
    fun igdbIdsRoundTripWithoutCollidingWithRawgNamespace() {
        val encoded = IgdbGameIds.encode(405433)
        assertTrue(IgdbGameIds.isIgdb(encoded))
        assertEquals(405433, IgdbGameIds.decode(encoded))
    }

    @Test
    fun monthRangeIncludesEveryCalendarMonth() {
        val months = ReleaseFeedRepositoryImpl.monthsBetween(
            LocalDate.of(2026, 8, 20),
            LocalDate.of(2026, 10, 2)
        )
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10)), months)
    }

    @Test
    fun synopsisProtectionRestoresGameAndPlatformNames() {
        val original = "Atelier Karia launches on Nintendo Switch 2 and follows Karia's journey."
        val protected = protectTranslationTerms(original, listOf("Atelier Karia", "Karia", "Nintendo Switch 2"))
        assertTrue(!protected.text.contains("Atelier Karia"))
        val simulatedTranslation = protected.text.replace("launches on", "será lançado no").replace("and follows", "e acompanha")
        val restored = restoreTranslationTerms(simulatedTranslation, protected.replacements)
        assertTrue(restored.contains("Atelier Karia"))
        assertTrue(restored.contains("Nintendo Switch 2"))
        assertTrue(restored.contains("Karia"))
    }
}
