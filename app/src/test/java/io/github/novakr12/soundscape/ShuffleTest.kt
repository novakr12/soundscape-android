package io.github.novakr12.soundscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class ShuffleTest {
    @Test
    fun shufflesOnlyTheSongsStillToCome() {
        val upcoming = listOf("c", "d", "e", "f", "g", "h")
        val mixed = Shuffle.shuffled(upcoming, Random(7))
        assertNotEquals(upcoming, mixed)
        assertEquals(upcoming.sorted(), mixed.sorted())
    }

    @Test
    fun putsSongsBackInOrderWithNewOnesLast() {
        assertEquals(listOf("c", "e", "x"), Shuffle.restored(listOf("c", "d", "e"), listOf("x", "e", "c")))
        assertEquals(listOf("a", "b", "a"), Shuffle.restored(listOf("a", "b", "a"), listOf("b", "a", "a")))
    }

    @Test
    fun movesEachSongToItsNewPlace() {
        val current = listOf("a", "b", "c", "d", "e")
        val wanted = listOf("e", "c", "d")
        val moves = Shuffle.moves(current, wanted, 2)
        val applied = current.toMutableList()
        moves.forEach { (from, to) -> applied.add(to, applied.removeAt(from)) }
        assertEquals(listOf("a", "b", "e", "c", "d"), applied)
    }
}
