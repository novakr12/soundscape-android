package io.github.novakr12.soundscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticesTest {
    private fun notice(id: Long, read: Boolean = false) = Notice(id, read, "Title $id", "Body $id", "/u/x")

    @Test
    fun readsTheServersAnswer() {
        val items = Notices.parse(
            """{"user":"ana","unread":2,"notifications":[
                {"id":7,"kind":"follow","read":false,"title":"New follower","body":"bo started following you.","url":"/u/bo"},
                {"id":6,"kind":"milestone","read":true,"title":"Milestone","body":"You reached 1,000 plays.","url":"//evil.example"},
                {"id":-1,"title":"Broken"}
            ]}""",
        )!!
        assertEquals(listOf(7L, 6L), items.map { it.id })
        assertEquals("/u/bo", items[0].url)
        assertNull(items[1].url)
        assertNull(Notices.parse("<html>"))
    }

    @Test
    fun theFirstCheckOnlyRemembersWhereThingsAre() {
        assertEquals(emptyList<Notice>() to 5L, Notices.fresh(listOf(notice(5), notice(3)), null))
        assertEquals(emptyList<Notice>() to 0L, Notices.fresh(emptyList(), null))
    }

    @Test
    fun showsNewUnreadOnesOldestFirst() {
        val (show, last) = Notices.fresh(listOf(notice(9), notice(8, read = true), notice(7), notice(4)), 5)
        assertEquals(listOf(7L, 9L), show.map { it.id })
        assertEquals(9L, last)
        assertEquals(emptyList<Notice>() to 9L, Notices.fresh(emptyList(), 9))
    }

    @Test
    fun foldsAPileIntoAFewNotifications() {
        val shown = Notices.summary((1L..5L).map { notice(it) })
        assertEquals(3, shown.size)
        assertEquals("Title 1", shown[0].title)
        assertEquals("3 more notifications", shown[2].body)
        assertEquals("/notifications", shown[2].url)
        assertEquals(2, Notices.summary(listOf(notice(1), notice(2))).size)
    }

    @Test
    fun asksOnlyForNewOnes() {
        assertEquals("https://m.example/api/notifications", Notices.address("https://m.example", null))
        assertTrue(Notices.address("https://m.example", 42).endsWith("?after=42"))
    }
}
