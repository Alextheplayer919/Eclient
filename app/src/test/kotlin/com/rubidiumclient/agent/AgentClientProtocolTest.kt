package com.rubidiumclient.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs the REAL [AgentClient] against [FakeAgent] over real loopback sockets. Each test pins down one
 * way the link can misbehave on a phone (see docs/BRIDGE.md).
 */
class AgentClientProtocolTest {

    /** Same logic as production, in milliseconds. */
    private val fast = Timing(
        connectTimeoutMs = 500, pingEveryMs = 100, deadAfterMs = 400,
        backoffMinMs = 40, backoffMaxMs = 300, rejectedBackoffMaxMs = 600, staleAfterMs = 250,
    )
    private val logs = CopyOnWriteArrayList<String>()
    private lateinit var agent: FakeAgent
    private var client: AgentClient? = null

    @Before fun setUp() { agent = FakeAgent() }

    @After fun tearDown() {
        client?.stop()
        agent.close()
    }

    private fun client(token: String = "secret"): AgentClient =
        AgentClient(token, "127.0.0.1", agent.port, "test", { t, m -> logs += "$t: $m" }, fast)
            .also { client = it; it.start() }

    private fun await(what: String, timeoutMs: Long = 4_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for: $what  [link=${client?.linkState} ${client?.health?.summary()} lastError=${client?.lastError}]")
    }

    // --------------------------------------------------------------------------------- happy path ---

    @Test fun becomesLive_parsesState_andMeasuresTheLink() {
        agent.includeCamera = true
        agent.announceProto = true
        val c = client()

        await("LIVE") { c.linkState == LinkState.LIVE }
        assertEquals(1.0, c.pose!!.x, 1e-9)
        assertTrue(c.fresh())
        assertTrue(c.connected)

        await("camera parsed") { c.camera != null }
        assertEquals(74.5f, c.camera!!.fovYDeg!!, 1e-4f)
        assertTrue(c.camera!!.hasPose)

        await("protocol announced") { c.agentProto == 1 }
        assertEquals("fake-r1", c.agentBuild)

        await("RTT measured") { c.health.rttMs != null }
        assertTrue("rtt=${c.health.rttMs}", c.health.rttMs!! < 250)
        await("push rate measured") { (c.health.pushHz() ?: 0.0) > 10.0 }
    }

    // ------------------------------------------------------------------- menu: silent but healthy ---

    @Test fun inAMenu_theAgentIsSilentButAlive_noReconnectChurn() {
        agent.snapshotAvailable = false          // main menu: the agent has nothing to push, but still answers ping
        val c = client()

        await("connected and answered") { c.linkState == LinkState.TCP_UP }
        Thread.sleep(1_200)                       // 3x deadAfterMs: "no data = dead" would have reconnected several times

        assertEquals(LinkState.TCP_UP, c.linkState)
        assertEquals("must stay on ONE connection", 1, c.health.connects)
        assertEquals(0, c.health.timeouts)
        assertFalse("no world data is not fresh data", c.fresh())
        assertTrue("pongs keep the link alive", c.health.rttMs != null)

        agent.snapshotAvailable = true            // the player joins a world
        await("LIVE after joining a world") { c.linkState == LinkState.LIVE }
        assertEquals(1, c.health.connects)
    }

    // -------------------------------------------------------------- frozen game: bytes ≠ freshness ---

    @Test fun aFrozenGame_isStalled_andNeverFresh_whileTheLinkStaysUp() {
        val c = client()
        await("LIVE") { c.linkState == LinkState.LIVE }

        agent.freezeTick = true                   // the bridge keeps re-sending the last snapshot at 20 Hz
        await("STALLED") { c.linkState == LinkState.STALLED }
        assertFalse("stale data must not pass as fresh", c.fresh())
        assertTrue(c.connected)
        assertTrue(c.health.stalls >= 1)
        assertEquals("the link itself is fine: no reconnect", 1, c.health.connects)

        agent.freezeTick = false
        await("LIVE again") { c.linkState == LinkState.LIVE }
        assertTrue(c.fresh())
    }

    @Test fun aLegacyAgentWithoutATickCounter_isTreatedAsAlwaysFresh() {
        agent.omitTick = true
        val c = client()
        await("LIVE") { c.linkState == LinkState.LIVE }
        Thread.sleep(500)
        assertEquals(LinkState.LIVE, c.linkState)
        assertTrue(c.fresh())
    }

    // ----------------------------------------------------------------------------- reconnecting ---

    @Test fun afterAnAgentRestart_itReconnectsQuickly_thenStaysPut() {
        val c = client()
        await("LIVE") { c.linkState == LinkState.LIVE }

        agent.closeAfterPushes = 3                // the game/agent goes away mid-session
        await("second connection") { c.health.connects >= 2 }
        agent.closeAfterPushes = -1               // …and comes back healthy

        await("LIVE again") { c.linkState == LinkState.LIVE }
        assertTrue(c.health.disconnects >= 1)
        val connectsNow = c.health.connects
        Thread.sleep(400)
        assertEquals("stable once back", connectsNow, c.health.connects)
    }

    @Test fun startingTheClientBeforeTheAgentExists_isFine_itJustWaits() {
        agent.acceptClients = false               // like: the app was started first, the game is not running yet
        val c = client()
        Thread.sleep(300)
        assertNotEquals(LinkState.LIVE, c.linkState)
        agent.acceptClients = true                // the game starts later
        await("LIVE once the agent is up") { c.linkState == LinkState.LIVE }
    }

    // ------------------------------------------------------------------------ misbehaving agents ---

    @Test fun aWrongToken_isRefused_backsOffSlowly_andNeverHotLoops() {
        val c = client(token = "wrong")
        await("refused") { c.health.rejections >= 1 }
        assertTrue(c.lastRefusal, c.lastRefusal.contains("token"))

        Thread.sleep(1_500)
        // backoff 40, 80, 160, 320, 600… => a handful of attempts. A loop that reset the backoff every time the
        // connection ended would have tried ~35 times in this window.
        assertTrue("attempts=${agent.accepted.get()}", agent.accepted.get() <= 9)
        assertTrue("every refusal came from an accepted attempt", agent.tokenRejections.get() in 1..agent.accepted.get())
        assertNotEquals(LinkState.LIVE, c.linkState)
        assertTrue(c.linkState == LinkState.REJECTED || c.linkState == LinkState.CONNECTING || c.linkState == LinkState.OFFLINE)
    }

    @Test fun aHungAgent_isDeclaredDead_andRetried() {
        agent.silent = true
        val c = client()
        await("declared dead") { c.health.timeouts >= 1 }
        assertTrue(c.lastError, c.lastError.contains("did not answer"))
        await("retried") { c.health.connects >= 2 }
        assertNotEquals(LinkState.LIVE, c.linkState)
    }

    @Test fun aConnectionQueuedInTheBacklog_isNeverReportedAsLive() {
        // The real agent serves one client at a time; the kernel still completes the TCP handshake of a queued
        // connection. "connect() succeeded" must therefore NOT be mistaken for "the agent is talking to us".
        agent.acceptClients = false
        val c = client()
        Thread.sleep(200)                         // inside the 400 ms window: TCP is up, nobody has answered yet
        assertEquals("connect() succeeded but the agent has not answered", LinkState.CONNECTING, c.linkState)
        assertFalse(c.connected)
        await("declared unresponsive") { c.health.timeouts >= 1 }
        assertEquals(0, agent.accepted.get())
        assertNotEquals(LinkState.LIVE, c.linkState)
        assertFalse(c.connected)

        agent.acceptClients = true                // the other client leaves
        await("LIVE once the agent serves us") { c.linkState == LinkState.LIVE }
    }

    // ----------------------------------------------------------------------------------- lifecycle ---

    @Test fun stop_isClean_andNoFurtherConnectionsAreMade() {
        val c = client()
        await("LIVE") { c.linkState == LinkState.LIVE }
        c.stop()
        assertEquals(LinkState.OFFLINE, c.linkState)
        val acceptedAtStop = agent.accepted.get()
        Thread.sleep(500)
        // at most one attempt can have been in flight at the instant of stop(); a live loop would add many
        assertTrue("accepted ${agent.accepted.get() - acceptedAtStop} more after stop()", agent.accepted.get() - acceptedAtStop <= 1)
        assertFalse(c.connected)
    }

    @Test fun onState_isInvokedOnEveryPush() {
        val c = client()
        val seen = java.util.concurrent.atomic.AtomicInteger()
        c.onState = { seen.incrementAndGet() }
        await("a few pushes") { seen.get() >= 5 }
        assertNotNull(c.pose)
    }
}
