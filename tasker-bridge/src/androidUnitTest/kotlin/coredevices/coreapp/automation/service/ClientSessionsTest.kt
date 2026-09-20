package coredevices.coreapp.automation.service

import coredevices.coreapp.automation.ClientRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ClientSessionsTest {
    private val client = ClientRecord("plugin", "0102", "Plugin", approvedAtMs = 0)
    @Test fun replacingClientRetiresOnlyItsPreviousSession() {
        val retired = mutableListOf<ClientSessions.Session>()
        val sessions = ClientSessions { retired += it }
        val first = sessions.replace(1, client)
        val other = sessions.replace(2, client.copy(packageName = "other"))
        val next = sessions.replace(1, client)
        assertNotEquals(first.token, next.token)
        assertNull(sessions.find(first.token))
        assertEquals(other, sessions.find(other.token))
        assertEquals(listOf(first), retired)
        sessions.remove(first.token)
        assertEquals(1, retired.size)
    }
    @Test fun changedUidSignerOrGrantCannotReuseSession() {
        val sessions = ClientSessions {}
        val session = sessions.replace(1, client)
        assertNull(sessions.forClient(2, client))
        assertNull(sessions.forClient(1, client.copy(certSha256 = "0304")))
        assertNull(sessions.forClient(1, client.copy(categories = setOf("health"))))
        assertEquals(session, sessions.forClient(1, client))
    }
    @Test fun shutdownRetiresEachSessionOnce() {
        val retired = mutableListOf<ClientSessions.Session>()
        val sessions = ClientSessions { retired += it }
        val first = sessions.replace(1, client)
        sessions.replace(2, client.copy(packageName = "other"))
        sessions.clear()
        sessions.clear()
        assertEquals(2, retired.size)
        assertNull(sessions.find(first.token))
    }
}
