package ai.koog.ktor.workspace

import ai.koog.agents.workspace.model.AgentWorkspaceEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentWorkspaceRoutesTest {
    @Test
    fun testWorkspaceEventMapsToReplayableSse(): Unit {
        val event = AgentWorkspaceEvent(
            sequence = 42,
            runId = "run-1",
            type = "tool.completed",
            data = JsonObject(mapOf("tool" to JsonPrimitive("search"))),
            createdAt = "2026-07-23T00:00:00Z",
        )

        val encoded = event.toServerSentEvent()

        assertEquals("42", encoded.id)
        assertEquals("tool.completed", encoded.event)
        assertTrue(encoded.data.orEmpty().contains("\"runId\":\"run-1\""))
    }
}
