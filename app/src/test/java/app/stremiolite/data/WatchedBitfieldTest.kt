package app.stremiolite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vetores do proprio stremio-core (stremio-watched-bitfield). */
class WatchedBitfieldTest {
    private val ids = (1..9).map { "tt2934286:1:$it" }
    private val sample = "tt2934286:1:5:5:eJyTZwAAAEAAIA=="

    @Test fun readsOfficialValue() {
        val w = WatchedBitfield.parse(sample, ids)
        assertTrue(w.reliable)
        assertEquals(ids.take(5).toSet(), w.watchedIds())
        assertFalse(w["tt2934286:1:6"])
    }

    @Test fun writesValueThatCanBeReadBack() {
        val w = WatchedBitfield.parse(sample, ids)
        w["tt2934286:1:7"] = true
        val text = w.serialize()
        assertTrue(text, text.startsWith("tt2934286:1:7:7:"))
        assertEquals(ids.take(5).toSet() + "tt2934286:1:7", WatchedBitfield.parse(text, ids).watchedIds())
    }

    @Test fun keepsWatchedEpisodesWhenAnEpisodeIsInsertedBefore() {
        val shifted = listOf("tt2934286:0:1") + ids
        assertEquals(ids.take(5).toSet(), WatchedBitfield.parse(sample, shifted).watchedIds())
    }

    @Test fun emptyValueStartsBlank() {
        val w = WatchedBitfield.parse(null, ids)
        assertTrue(w.reliable)
        assertTrue(w.watchedIds().isEmpty())
    }

    @Test fun officialEmptyValueCanBeReplaced() {
        assertTrue(WatchedBitfield.parse("undefined:1:eJwDAAAAAAE=", ids).reliable)
    }

    @Test fun unknownAnchorWithHistoryIsNotReliable() {
        val w = WatchedBitfield.parse("tt999:1:5:5:eJyTZwAAAEAAIA==", ids)
        assertFalse(w.reliable)
        assertTrue(w.watchedIds().isEmpty())
    }

    @Test fun corruptedValueIsNotReliable() {
        assertFalse(WatchedBitfield.parse("tt2934286:1:5:5:@@@", ids).reliable)
        assertFalse(WatchedBitfield.parse("lixo", ids).reliable)
    }
}
