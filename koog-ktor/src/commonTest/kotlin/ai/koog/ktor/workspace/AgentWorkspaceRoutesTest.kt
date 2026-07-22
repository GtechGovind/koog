package ai.koog.ktor.workspace

import ai.koog.agents.workspace.AgentWorkspaceController
import ai.koog.agents.workspace.InMemoryAgentWorkspaceStore
import ai.koog.agents.workspace.model.AgentWorkspaceEvent
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentWorkspaceRoutesTest {
    @Test
    fun testResumeRouteDelegatesCheckpointLoadingToHost() = testApplication {
        var resumedRunId: String? = null
        application {
            routing {
                agentWorkspaceRoutes(
                    controller = AgentWorkspaceController(InMemoryAgentWorkspaceStore()),
                    resumeHandler = AgentWorkspaceResumeHandler { resumedRunId = it },
                )
            }
        }

        val response = client.post("/agent-workspace/runs/run-1/resume")

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("run-1", resumedRunId)
    }

    @Test
    fun testWorkspaceEventMapsToReplayableSse() {
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
