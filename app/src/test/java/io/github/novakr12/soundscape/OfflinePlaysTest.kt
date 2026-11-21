package io.github.novakr12.soundscape

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflinePlaysTest {
    private val plays = listOf(
        OfflinePlay("Band", "One", "Album", 1_700_000_000),
        OfflinePlay("Band", "Two", null, 1_700_000_300),
    )

    @Test
    fun remembersPlaysAcrossRestarts() {
        assertEquals(plays, OfflinePlays.decode(OfflinePlays.encode(plays)))
        assertEquals(emptyList<OfflinePlay>(), OfflinePlays.decode("not json"))
        assertEquals(
            listOf(plays[0]),
            OfflinePlays.decode("""[${JSONObject().put("artist", "Band").put("title", "One").put("album", "Album").put("playedAt", 1_700_000_000)},{"artist":"","title":"x","playedAt":5},{"artist":"a","title":"b","playedAt":0}]"""),
        )
    }

    @Test
    fun sendsThemWithTheirOwnTimes() {
        val body = JSONObject(OfflinePlays.body(plays))
        assertEquals("import", body.getString("listen_type"))
        val payload = body.getJSONArray("payload")
        assertEquals(2, payload.length())
        assertEquals(1_700_000_000, payload.getJSONObject(0).getLong("listened_at"))
        val first = payload.getJSONObject(0).getJSONObject("track_metadata")
        assertEquals("Band", first.getString("artist_name"))
        assertEquals("One", first.getString("track_name"))
        assertEquals("Album", first.getString("release_name"))
        assertFalse(payload.getJSONObject(1).getJSONObject("track_metadata").has("release_name"))
    }

    @Test
    fun removesOnlyTheSentBatchAndKeepsNewPlays() {
        val added = OfflinePlay("Band", "Three", null, 1_700_000_600)
        assertEquals(listOf(added), OfflinePlays.withoutSent(plays + added, plays))
        assertEquals(emptyList<OfflinePlay>(), OfflinePlays.withoutSent(plays, plays))
        // Leading play evicted during send; only remaining overlapping sent item is dropped
        assertEquals(listOf(added), OfflinePlays.withoutSent(listOf(plays[1], added), plays))
        // Unrelated queue is left untouched
        assertEquals(listOf(added), OfflinePlays.withoutSent(listOf(added), plays))
    }
}
