package com.rembrowser.rembrowse.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyExchangeWatchdogTest {

    @Test
    fun armed_firesAfterItsTimeout() {
        val latch = CountDownLatch(1)
        val watchdog = KeyExchangeWatchdog(100) { latch.countDown() }
        assertFalse(watchdog.fired)
        watchdog.arm()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(watchdog.fired)
        watchdog.stop()
    }

    @Test
    fun disarmedBeforeTheTimeout_doesNotFire() {
        val count = AtomicInteger()
        val watchdog = KeyExchangeWatchdog(300) { count.incrementAndGet() }
        watchdog.arm()
        watchdog.disarm()
        Thread.sleep(900)
        assertEquals(0, count.get())
        assertFalse(watchdog.fired)
        watchdog.stop()
    }

    @Test
    fun neverArmed_doesNotFire() {
        val count = AtomicInteger()
        val watchdog = KeyExchangeWatchdog(100) { count.incrementAndGet() }
        Thread.sleep(500)
        assertEquals(0, count.get())
        assertFalse(watchdog.fired)
        watchdog.stop()
    }

    @Test
    fun rearming_restartsTheClock() {
        val start = System.nanoTime()
        val firedAt = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val watchdog = KeyExchangeWatchdog(800) {
            firedAt.set(((System.nanoTime() - start) / 1_000_000).toInt())
            latch.countDown()
        }
        watchdog.arm()
        Thread.sleep(500)
        watchdog.arm()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        // Counted from the second arm (500 ms in), not the first
        assertTrue("fired at ${firedAt.get()} ms", firedAt.get() >= 1_250)
        assertTrue(watchdog.fired)
        watchdog.stop()
    }

    @Test
    fun disarmedThenRearmed_firesOnlyAfterTheNewTimeout() {
        val latch = CountDownLatch(1)
        val watchdog = KeyExchangeWatchdog(300) { latch.countDown() }
        watchdog.arm()
        watchdog.disarm()
        Thread.sleep(600)
        assertFalse(watchdog.fired)
        watchdog.arm()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(watchdog.fired)
        watchdog.stop()
    }

    @Test
    fun afterStop_nothingFires_andArmingDoesNothing() {
        val count = AtomicInteger()
        val watchdog = KeyExchangeWatchdog(200) { count.incrementAndGet() }
        watchdog.arm()
        watchdog.stop()
        watchdog.arm()
        Thread.sleep(800)
        assertEquals(0, count.get())
        assertFalse(watchdog.fired)
    }

    @Test
    fun aZeroTimeout_neverFires() {
        val count = AtomicInteger()
        val watchdog = KeyExchangeWatchdog(0) { count.incrementAndGet() }
        watchdog.arm()
        Thread.sleep(300)
        assertEquals(0, count.get())
        watchdog.stop()
    }
}
