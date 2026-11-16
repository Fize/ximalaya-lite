package com.shixi.client

import android.app.Instrumentation
import android.os.Bundle
import com.shixi.protocol.Item
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaybackCatalogueVerification : Instrumentation() {
 override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
 override fun onStart() {
  val result = Bundle()
  try {
   var now = 0L
   val calls = AtomicInteger()
   val started = CountDownLatch(1)
   val unblock = CountDownLatch(1)
   val cache = PlaybackCatalogue({ album, page ->
    calls.incrementAndGet(); started.countDown(); check(unblock.await(10, TimeUnit.SECONDS))
    listOf(Item(1, "test", true, album, page = page))
   }, { now })
   val workers = Executors.newFixedThreadPool(2)
   try {
    val first = workers.submit<List<Item>> { cache.tracks(7, 1) }
    check(started.await(10, TimeUnit.SECONDS))
    val second = workers.submit<List<Item>> { cache.tracks(7, 1) }
    unblock.countDown()
    check(first.get(10, TimeUnit.SECONDS) == second.get(10, TimeUnit.SECONDS))
    check(calls.get() == 1)
    now = 30_001; cache.tracks(7, 1); check(calls.get() == 2)
    cache.tracks(7, 2); check(calls.get() == 3)
   } finally { workers.shutdownNow() }
   val entered = CountDownLatch(1)
   val release = CountDownLatch(1)
   val interruptCalls = AtomicInteger()
   val interruptCache = PlaybackCatalogue({ album, page ->
    interruptCalls.incrementAndGet(); entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
    listOf(Item(1, "test", true, album, page = page))
   })
   val owner = Thread { interruptCache.tracks(9, 1) }; owner.start(); check(entered.await(10, TimeUnit.SECONDS))
   val restoredInterrupt = java.util.concurrent.atomic.AtomicBoolean()
   val waiting = Thread { try { interruptCache.tracks(9, 1) } catch (_: InterruptedException) { restoredInterrupt.set(Thread.currentThread().isInterrupted) } }
   waiting.start(); Thread.sleep(100); waiting.interrupt(); waiting.join(2000); check(!waiting.isAlive && restoredInterrupt.get())
   release.countDown(); owner.join(2000); check(!owner.isAlive)
   check(interruptCache.tracks(9, 1).size == 1 && interruptCalls.get() == 1)
   var failures = 0
   val retry = PlaybackCatalogue({ _, _ -> if (failures++ == 0) throw IOException("offline") else listOf(Item(1, "test", true, 7)) })
   check(runCatching { retry.tracks(7, 1) }.isFailure)
   check(retry.tracks(7, 1).size == 1 && failures == 2)
   result.putString("result", "PASS concurrent fetch sharing, expiry, page separation, interrupted waiter and failure retry")
   finish(-1, result)
  } catch (failure: Throwable) { result.putString("failure", failure.stackTraceToString()); finish(0, result) }
 }
}
