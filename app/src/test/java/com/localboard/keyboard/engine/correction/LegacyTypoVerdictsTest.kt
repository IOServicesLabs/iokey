package com.localboard.keyboard.engine.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTypoVerdictsTest {
    private val ts = 1_700_000_000_000L

    @Test
    fun readsDatedSuppressions() {
        assertEquals(
            listOf(
                LegacyTypoVerdicts.Suppression("teh", ts),
                LegacyTypoVerdicts.Suppression("faceid", ts + 1),
            ),
            LegacyTypoVerdicts.parseSuppressions("teh@$ts,faceid@${ts + 1}"),
        )
    }

    @Test
    fun readsBareLegacySuppressionsWithNoDate() {
        assertEquals(
            listOf(
                LegacyTypoVerdicts.Suppression("teh", null),
                LegacyTypoVerdicts.Suppression("oof", ts),
            ),
            LegacyTypoVerdicts.parseSuppressions("teh,oof@$ts"),
        )
    }

    @Test
    fun readsStrikes() {
        assertEquals(
            listOf(
                LegacyTypoVerdicts.Strike("teh", 1, ts),
                LegacyTypoVerdicts.Strike("oof", 2, ts + 5),
            ),
            LegacyTypoVerdicts.parseStrikes("teh=1@$ts,oof=2@${ts + 5}"),
        )
    }

    @Test
    fun anAddressIsNotBlamedOnTheWordBeforeItsAtSign() {
        assertEquals(
            listOf(
                LegacyTypoVerdicts.Suppression("me@site.com", ts),
                LegacyTypoVerdicts.Suppression("me@site.com", null),
            ),
            LegacyTypoVerdicts.parseSuppressions("me@site.com@$ts,me@site.com"),
        )
        assertEquals(setOf("me@site.com"), LegacyTypoVerdicts.struckWords("me@site.com@$ts", null))
    }

    @Test
    fun anEmptyStoreHoldsNothing() {
        assertEquals(emptyList<Any>(), LegacyTypoVerdicts.parseSuppressions(null))
        assertEquals(emptyList<Any>(), LegacyTypoVerdicts.parseSuppressions(""))
        assertEquals(emptyList<Any>(), LegacyTypoVerdicts.parseSuppressions(" ,, "))
        assertEquals(emptyList<Any>(), LegacyTypoVerdicts.parseStrikes(null))
        assertEquals(emptyList<Any>(), LegacyTypoVerdicts.parseStrikes(""))
        assertEquals(emptyList<Any>(), LegacyTypoVerdicts.parseStrikes(" ,, "))
        assertTrue(LegacyTypoVerdicts.struckWords(null, null).isEmpty())
    }

    @Test
    fun entriesWithNoWordAreDropped() {
        assertEquals(emptyList<Any>(), LegacyTypoVerdicts.parseSuppressions("@$ts"))
        assertEquals(emptyList<Any>(), LegacyTypoVerdicts.parseStrikes("=1@$ts,noequals,=,"))
    }

    @Test
    fun aStrikeWithAnUnreadableValueStillNamesItsWord() {
        assertEquals(
            listOf(
                LegacyTypoVerdicts.Strike("teh", null, null),
                LegacyTypoVerdicts.Strike("oof", 1, null),
                LegacyTypoVerdicts.Strike("zork", null, ts),
                LegacyTypoVerdicts.Strike("bork", 2, null),
            ),
            LegacyTypoVerdicts.parseStrikes("teh=,oof=1,zork=x@$ts,bork=2@soon"),
        )
    }

    @Test
    fun wordsAreLowercasedAndTrimmed() {
        assertEquals(
            setOf("teh", "oof", "zork"),
            LegacyTypoVerdicts.struckWords(" TEH ,Oof@$ts", " ZORK =1@$ts"),
        )
    }

    @Test
    fun struckWordsIsTheUnionOfBothListsWithEachWordOnce() {
        assertEquals(
            setOf("teh", "faceid", "oof"),
            LegacyTypoVerdicts.struckWords("teh@$ts,faceid", "oof=1@$ts,teh=1@$ts"),
        )
    }

    @Test
    fun anExpiredVerdictStillCounts() {
        // The parser has no clock. Only the upgrade reads this, and it treats every listed word alike.
        assertEquals(setOf("teh", "oof"), LegacyTypoVerdicts.struckWords("teh@1", "oof=1@1"))
    }
}
