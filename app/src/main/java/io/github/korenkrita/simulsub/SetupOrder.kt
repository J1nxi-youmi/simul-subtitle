package io.github.korenkrita.simulsub

/**
 * Orders the settings a running session switches to, and its one first-use probe of the user's own translation
 * service.
 *
 * A change bumps the version; a rebuild may publish only while its version is still the newest, and the check and the
 * publication happen under the same lock as the bump, so a change made while a rebuild builds or probes is never lost:
 * it either drops that rebuild or is picked up by the next one.
 *
 * A probe is identified by a token, not by the service it probes: a superseded probe of service B must not pass for a
 * later, wanted probe of the same B. Two probes of one service never run at once.
 *
 * Shares the caller's [lock], so a session can cancel a probe's requests and the objects a setup holds under the same
 * boundary. No Android types: [SetupOrderTest] drives the interleavings on the JVM.
 *
 * @param K the probe's key (the BYOK address, model and key)
 * @param R what a probe finds (the switch that turns thinking off; null = none worked)
 */
class SetupOrder<K, R>(private val lock: Any = Any()) {
    /** the newest change; a rebuild tags the setup it builds with this */
    private var wanted = 0
    /** the one probe in flight, and its identity (0 = none) */
    private var token = 0L
    private var probing: K? = null
    private var closed = false
    /** what this session probed ([K] in it, also for an inconclusive answer) */
    private val results = HashMap<K, R?>()
    /** a probe that ended without an answer, shown while its service is in use */
    private val trouble = HashMap<K, String>()

    /** A settings change: a rebuild publishes from now on. False once the session ended (nothing to publish). */
    fun changed(): Boolean = synchronized(lock) {
        if (closed) return false
        wanted++
        true
    }

    /** "保存" of the user's own service: a change, and a probe in flight is no longer wanted. */
    fun byokSaved(): Boolean = synchronized(lock) {
        if (closed) return false
        wanted++
        dropProbe()
        true
    }

    /** The newest change's version. */
    fun version(): Int = synchronized(lock) { wanted }

    /**
     * Registers a probe of [key] as the only one, for the settings of version [v]. Null: the session ended, [v] was
     * overtaken by a newer change (that one builds again), or [key] is already being probed (no second attempt).
     */
    fun beginProbe(key: K, v: Int): Long? = synchronized(lock) {
        if (closed || v != wanted || probing == key) return null
        token++
        probing = key
        token
    }

    /**
     * Records what probe [t] of [key] found, and [trouble] if it ended without an answer (the service is unavailable;
     * shown while it is in use, this session does not try again). False: not the newest attempt, nothing is recorded.
     */
    fun endProbe(key: K, t: Long, result: R?, trouble: String? = null): Boolean = synchronized(lock) {
        if (closed || t != token || probing != key) return false
        probing = null
        token++ // this attempt is over: a later probe of the same key has to register again
        results[key] = result
        if (trouble != null) this.trouble[key] = trouble
        true
    }

    /** Probe [t] is still the wanted one (the probe thread registers its cancel handle only then, under the lock). */
    fun current(t: Long): Boolean = synchronized(lock) { !closed && t == token && probing != null }

    /** [key] is being probed right now (that attempt's requests are the ones to cancel). */
    fun isProbing(key: K): Boolean = synchronized(lock) { probing == key }

    /** This session already probed [key] (see [result]). */
    fun knows(key: K): Boolean = synchronized(lock) { results.containsKey(key) }

    /** What this session probed for [key]; valid when [knows]. */
    fun result(key: K): R? = synchronized(lock) { results[key] }

    /** The message of a probe of [key] that ended without an answer, if that happened. */
    fun trouble(key: K): String? = synchronized(lock) { trouble[key] }

    /**
     * Publishes version [v] through [pub] under the same lock that bumps the version: [pub] runs only while [v] is
     * still the newest, and false = dropped (a newer change arrived, or the session ended).
     */
    fun publish(v: Int, pub: () -> Boolean): Boolean = synchronized(lock) {
        if (closed || v != wanted) return false
        pub()
    }

    /** The session ended: no further change, probe or publication. */
    fun close() = synchronized(lock) {
        closed = true
        dropProbe()
    }

    private fun dropProbe() {
        probing = null
        token++
    }
}
