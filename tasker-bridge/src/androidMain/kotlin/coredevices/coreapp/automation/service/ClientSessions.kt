package coredevices.coreapp.automation.service

import coredevices.coreapp.automation.ClientRecord
import java.util.UUID

class ClientSessions(private val retired: (Session) -> Unit) {
    data class Session(val token: String, val uid: Int, val client: ClientRecord)
    private val sessions = mutableMapOf<String, Session>()

    @Synchronized fun replace(uid: Int, client: ClientRecord): Session {
        sessions.values.filter { it.client.packageName == client.packageName }.toList().forEach { remove(it.token) }
        val session = Session(UUID.randomUUID().toString(), uid, client)
        sessions[session.token] = session
        return session
    }
    @Synchronized fun find(token: String): Session? = sessions[token]
    @Synchronized fun forClient(uid: Int, client: ClientRecord): Session? =
        sessions.values.singleOrNull { it.uid == uid && it.client == client }
    @Synchronized fun remove(token: String) { sessions.remove(token)?.let(retired) }
    @Synchronized fun clear() { sessions.keys.toList().forEach(::remove) }
}
