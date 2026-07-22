package ai.koog.ktor.workspace

import ai.koog.agents.workspace.AgentWorkspaceController
import ai.koog.agents.workspace.model.AgentCancellationMode
import ai.koog.agents.workspace.model.AgentInputResponse
import ai.koog.agents.workspace.model.AgentWorkspaceEvent
import ai.koog.agents.workspace.model.AgentWorkspaceRunStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Request body for cooperative workspace cancellation. */
@Serializable
public data class AgentWorkspaceCancellationRequest(
    val mode: AgentCancellationMode,
    val reason: String? = null,
)

/** Host callback that restores application input and a Persistence checkpoint before resuming a run. */
public fun interface AgentWorkspaceResumeHandler {
    /** Claims and resumes [runId], or throws when the persisted run cannot be resumed. */
    public suspend fun resume(runId: String)
}

/**
 * Installs generic control and replayable SSE routes for [controller].
 *
 * The host application must install Ktor ContentNegotiation with kotlinx JSON and the SSE plugin.
 * Routes are mounted below [path] as `/runs/{runId}/events`, `/answers`, `/cancel`, and,
 * when [resumeHandler] is supplied, `/resume`. The callback keeps checkpoint and input loading
 * application-owned while the framework supplies a consistent transport contract.
 */
public fun Route.agentWorkspaceRoutes(
    controller: AgentWorkspaceController,
    path: String = "/agent-workspace",
    pollIntervalMillis: Long = 250L,
    resumeHandler: AgentWorkspaceResumeHandler? = null,
) {
    require(pollIntervalMillis in 50L..60_000L) { "SSE poll interval must be between 50ms and 60s" }

    sse("$path/runs/{runId}/events") {
        val runId = requireNotNull(call.parameters["runId"]) { "Missing run id" }
        var cursor = call.request.queryParameters["after"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

        while (true) {
            val events = controller.events(runId, cursor)
            events.forEach { event ->
                send(event.toServerSentEvent())
                cursor = event.sequence
            }

            val status = controller.snapshot(runId)?.status
            if (status in TERMINAL_STATUSES && events.isEmpty()) break
            delay(pollIntervalMillis)
        }
    }

    post("$path/runs/{runId}/answers") {
        val runId = requireNotNull(call.parameters["runId"]) { "Missing run id" }
        val receipt = controller.answer(runId, call.receive<AgentInputResponse>())
        call.respond(HttpStatusCode.Accepted, receipt)
    }

    post("$path/runs/{runId}/cancel") {
        val runId = requireNotNull(call.parameters["runId"]) { "Missing run id" }
        val request = call.receive<AgentWorkspaceCancellationRequest>()
        controller.requestCancellation(runId, request.mode, request.reason)
        call.respond(HttpStatusCode.Accepted)
    }

    if (resumeHandler != null) {
        post("$path/runs/{runId}/resume") {
            val runId = requireNotNull(call.parameters["runId"]) { "Missing run id" }
            resumeHandler.resume(runId)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}

/** Converts a replayable workspace event to its SSE representation. */
public fun AgentWorkspaceEvent.toServerSentEvent(json: Json = WORKSPACE_JSON): ServerSentEvent = ServerSentEvent(
    data = json.encodeToString(AgentWorkspaceEvent.serializer(), this),
    event = type,
    id = sequence.toString(),
)

private val WORKSPACE_JSON: Json = Json {
    encodeDefaults = true
    explicitNulls = false
}

private val TERMINAL_STATUSES: Set<AgentWorkspaceRunStatus> = setOf(
    AgentWorkspaceRunStatus.CANCELLED,
    AgentWorkspaceRunStatus.COMPLETED,
    AgentWorkspaceRunStatus.FAILED,
)
