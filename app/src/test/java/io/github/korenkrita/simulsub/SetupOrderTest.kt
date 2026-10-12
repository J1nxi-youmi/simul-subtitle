package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SetupOrderTest {
    @Test
    fun aChangeDuringTheBuildDropsThatBuild() {
        val o = SetupOrder<String, Int>()
        val v = o.version()
        assertTrue(o.changed()) // e.g. the language, while the rebuild for v was building
        var ran = false
        assertFalse(o.publish(v) { ran = true; true })
        assertFalse(ran)
        assertTrue(o.publish(o.version()) { true })
    }

    @Test
    fun aChangeDuringThePublicationWaitsAndIsNotLost() {
        val lock = Any()
        val o = SetupOrder<String, Int>(lock)
        val v = o.version()
        val inside = CountDownLatch(1); val go = CountDownLatch(1); val bumped = CountDownLatch(1)
        val pub = Thread { o.publish(v) { inside.countDown(); go.await(); true } }
        pub.start()
        assertTrue(inside.await(2, TimeUnit.SECONDS))
        val change = Thread { o.changed(); bumped.countDown() }
        change.start()
        assertFalse(bumped.await(200, TimeUnit.MILLISECONDS)) // held behind the publication
        go.countDown()
        assertTrue(bumped.await(2, TimeUnit.SECONDS))
        pub.join(); change.join()
        assertEquals(v + 1, o.version()) // so the next rebuild sees it and publishes it
        assertFalse(o.publish(v) { true })
    }

    @Test
    fun aSaveBeforeTheProbeRegistersStopsIt() {
        val o = SetupOrder<String, Int>()
        val v = o.version() // the rebuild read B with this version
        o.byokSaved() // A is saved before that rebuild registered its probe of B
        assertNull(o.beginProbe("B", v))
        assertNotNull(o.beginProbe("A", o.version()))
    }

    @Test
    fun aSupersededProbeOfTheSameServiceDoesNotPassForTheNewOne() {
        val o = SetupOrder<String, Int>()
        val old = o.beginProbe("B", o.version())!!
        o.byokSaved() // A
        o.byokSaved() // B again
        val new = o.beginProbe("B", o.version())!!
        assertFalse(o.endProbe("B", old, 1))
        assertFalse(o.current(old)); assertTrue(o.current(new))
        assertFalse(o.endProbe("B", old, 1, "gone"))
        assertFalse(o.knows("B")); assertNull(o.trouble("B"))
        assertTrue(o.isProbing("B")) // the new probe is untouched
        assertTrue(o.endProbe("B", new, 2))
        assertTrue(o.knows("B")); assertEquals(2, o.result("B"))
        assertFalse(o.isProbing("B"))
    }

    @Test
    fun oneProbePerServiceAtATime() {
        val o = SetupOrder<String, Int>()
        val t = o.beginProbe("B", o.version())!!
        o.changed() // e.g. the scene: the next rebuild must not start a second probe of B
        assertNull(o.beginProbe("B", o.version()))
        assertTrue(o.isProbing("B"))
        assertTrue(o.endProbe("B", t, null))
        assertTrue(o.knows("B")); assertNull(o.result("B")) // inconclusive, still known
        // a rebuild that saw B unknown before the probe ended does not probe it again
        assertNull(o.beginProbe("B", o.version()))
        o.changed() // the finished probe's own rebuild trigger, after that
        assertNull(o.beginProbe("B", o.version()))
    }

    @Test
    fun aProbeWithoutAnswerIsKnownWithItsTrouble() {
        val o = SetupOrder<String, Int>()
        val t = o.beginProbe("B", o.version())!!
        assertTrue(o.current(t))
        assertTrue(o.endProbe("B", t, null, "down"))
        assertEquals("down", o.trouble("B")); assertTrue(o.knows("B"))
        assertFalse(o.isProbing("B")); assertFalse(o.current(t))
    }

    @Test
    fun nothingAfterTheEnd() {
        val o = SetupOrder<String, Int>()
        val t = o.beginProbe("B", o.version())!!
        val v = o.version()
        o.close()
        assertFalse(o.isProbing("B"))
        assertFalse(o.endProbe("B", t, 1)); assertFalse(o.current(t))
        assertNull(o.beginProbe("A", v))
        assertFalse(o.publish(v) { true })
        assertFalse(o.changed()); assertFalse(o.byokSaved())
    }
}
