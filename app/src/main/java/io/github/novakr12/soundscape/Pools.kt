package io.github.novakr12.soundscape

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object Pools {
    val io: ExecutorService = bounded("soundscape-io", 4)

    fun serial(name: String): ExecutorService = bounded(name, 1)

    fun bounded(name: String, maxThreads: Int): ExecutorService = ThreadPoolExecutor(
        maxThreads,
        maxThreads,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        daemonFactory(name),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    private fun daemonFactory(prefix: String): ThreadFactory {
        val count = AtomicInteger(0)
        return ThreadFactory { task ->
            Thread(task, "$prefix-${count.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }
}
