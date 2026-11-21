package io.github.novakr12.soundscape

import kotlin.random.Random

object Shuffle {
    fun <T> shuffled(upcoming: List<T>, random: Random = Random.Default): List<T> = upcoming.shuffled(random)

    fun <T> restored(original: List<T>, upcoming: List<T>): List<T> {
        val left = upcoming.toMutableList()
        val out = ArrayList<T>(upcoming.size)
        for (wanted in original) {
            val at = left.indexOf(wanted)
            if (at >= 0) out.add(left.removeAt(at))
        }
        return out + left
    }

    fun moves(current: List<Any?>, wanted: List<Any?>, from: Int): List<Pair<Int, Int>> {
        val now = current.toMutableList()
        val out = mutableListOf<Pair<Int, Int>>()
        wanted.forEachIndexed { offset, item ->
            val target = from + offset
            val at = (target until now.size).firstOrNull { now[it] == item } ?: return@forEachIndexed
            if (at != target) {
                now.add(target, now.removeAt(at))
                out.add(at to target)
            }
        }
        return out
    }
}
