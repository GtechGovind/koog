package ai.koog.agents.example.workspace

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.agents.workspace.AgentWorkspaceController
import ai.koog.agents.workspace.model.AgentArtifactReference
import ai.koog.agents.workspace.model.AgentInputOption
import ai.koog.agents.workspace.model.AgentInputRequest
import ai.koog.agents.workspace.model.AgentInputRequestKind
import ai.koog.agents.workspace.model.AgentWorkspaceContent
import ai.koog.ktor.workspace.AgentWorkspaceResumeHandler
import ai.koog.ktor.workspace.agentWorkspaceRoutes
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.serialization.typeToken
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Restart-safe Ktor workspace example.
 *
 * Start a run, read its replayable SSE stream, answer the typed approval, and call resume. The
 * JSON workspace store and Koog Persistence checkpoints remain in `.koog-workspace-example`, so
 * the server can be stopped after suspension and restarted before the answer or resume request.
 */
@OptIn(ExperimentalUuidApi::class)
fun main() {
    val root = Path.of(".koog-workspace-example").toAbsolutePath()
    val workspaceStore = JvmJsonAgentWorkspaceStore(root.resolve("workspace.json"))
    val checkpointStore = JVMFilePersistenceStorageProvider(root.resolve("checkpoints"))
    val controller = AgentWorkspaceController(workspaceStore)
    val executor = simpleOllamaAIExecutor()
    val runs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    embeddedServer(CIO, host = "127.0.0.1", port = 8080) {
        install(ContentNegotiation) { json() }
        install(SSE)
        monitor.subscribe(ApplicationStopped) {
            runs.cancel()
            executor.close()
        }

        routing {
            post("/example/runs") {
                val request = call.receive<StartWorkspaceRunRequest>()
                val runId = request.runId ?: Uuid.random().toString()
                workspaceStore.saveInput(runId, request.service)
                runs.launch {
                    controller.runAgent(
                        runId,
                        createAgent(controller, workspaceStore, checkpointStore, executor),
                        request.service,
                    )
                }
                call.respond(HttpStatusCode.Accepted, StartWorkspaceRunResponse(runId))
            }

            post("/example/runs/{runId}/guidance") {
                val runId = requireNotNull(call.parameters["runId"]) { "Missing run id" }
                val request = call.receive<QueueGuidanceRequest>()
                require(request.guidance.isNotBlank()) { "Guidance cannot be blank" }
                workspaceStore.enqueueGuidance(runId, request.guidance)
                call.respond(HttpStatusCode.Accepted)
            }

            agentWorkspaceRoutes(
                controller = controller,
                resumeHandler = AgentWorkspaceResumeHandler { runId ->
                    controller.revalidateDecision(runId)
                    val input = requireNotNull(workspaceStore.loadInput(runId)) { "Missing input for run '$runId'" }
                    val interruption = requireNotNull(controller.snapshot(runId)?.interruption) {
                        "Run '$runId' has no pending interruption"
                    }
                    val checkpoint = requireNotNull(
                        checkpointStore.getCheckpoints(runId)
                            .firstOrNull { it.checkpointId == interruption.checkpointId }
                    ) { "Checkpoint '${interruption.checkpointId}' is unavailable" }
                    runs.launch {
                        controller.resumeAgent(
                            runId,
                            createAgent(controller, workspaceStore, checkpointStore, executor),
                            input,
                            checkpoint,
                        )
                    }
                },
            )
        }
    }.start(wait = true)
}

internal fun createAgent(
    controller: AgentWorkspaceController,
    workspaceStore: JvmJsonAgentWorkspaceStore,
    checkpoints: JVMFilePersistenceStorageProvider,
    executor: PromptExecutor,
): AIAgent<String, String> {
    val lookup = ServiceEvidenceTool
    val strategy = strategy<String, String>("workspace-example") {
        val gatherEvidence by node<String, String>("gather-evidence") { service ->
            controller.beforeAction(runId)
            controller.emitContent(runId, progress("evidence", "Querying the service inventory"))
            val evidence = lookup.execute(ServiceEvidenceTool.Args(service))
            controller.emitContent(runId, progress("evidence", "Found bounded operational evidence"))
            evidence
        }
        val requestApproval by node<String, String>("request-approval") { evidence ->
            if (controller.snapshot(runId)?.decisionReceipt?.revalidated == true) return@node evidence
            controller.suspendForInput(
                context = this,
                request = AgentInputRequest(
                    id = "publish-$runId",
                    kind = AgentInputRequestKind.APPROVAL,
                    prompt = "Publish the generated investigation brief?",
                    options = listOf(
                        AgentInputOption("approve", "Publish"),
                        AgentInputOption("reject", "Do not publish"),
                    ),
                ),
                requestHash = "publish:$runId:$evidence",
            )
        }
        val publish by node<String, String>("publish") { evidence ->
            controller.afterNode(runId)
            val selectedOptions = controller.snapshot(runId)?.decisionReceipt?.response?.selectedOptionIds
            val approved = selectedOptions == listOf("approve")
            val guidance = workspaceStore.loadGuidance(runId)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "\n\nQueued guidance:\n", separator = "\n") { "- $it" }
                .orEmpty()
            val markdown = if (approved) {
                "# Investigation brief\n\n$evidence$guidance\n\nApproval recorded and the artifact is ready."
            } else {
                "# Investigation brief\n\n$evidence$guidance\n\nPublication was declined."
            }
            controller.emitContent(
                runId,
                AgentWorkspaceContent(
                    id = "markdown-$runId",
                    kind = "markdown",
                    title = "Investigation brief",
                    mimeType = "text/markdown",
                    payload = buildJsonObject { put("text", markdown) },
                ),
            )
            if (approved) {
                val reference = AgentArtifactReference(
                    id = "brief-$runId",
                    title = "Investigation brief",
                    mimeType = "text/markdown",
                    uri = "artifact://workspace-example/$runId/brief.md",
                )
                controller.emitContent(
                    runId,
                    AgentWorkspaceContent(
                        id = reference.id,
                        kind = "artifact_reference",
                        title = reference.title,
                        mimeType = reference.mimeType,
                        payload = Json.encodeToJsonElement(AgentArtifactReference.serializer(), reference) as JsonObject,
                    ),
                )
            }
            markdown
        }

        edge(nodeStart forwardTo gatherEvidence)
        edge(gatherEvidence forwardTo requestApproval)
        edge(requestApproval forwardTo publish)
        edge(publish forwardTo nodeFinish)
    }

    return AIAgent(
        promptExecutor = executor,
        strategy = strategy,
        agentConfig = AIAgentConfig(
            prompt = prompt("workspace-example") { system("Produce a concise evidence-grounded investigation brief.") },
            model = OllamaModels.Meta.LLAMA_3_2,
            maxAgentIterations = 8,
        ),
        toolRegistry = ToolRegistry { tool(lookup) },
        id = "workspace-example-agent",
    ) {
        install(Persistence) { storage = checkpoints }
    }
}

private fun progress(stage: String, message: String) = AgentWorkspaceContent(
    id = "progress-$stage-${Clock.System.now()}",
    kind = "progress",
    payload = JsonObject(mapOf("stage" to JsonPrimitive(stage), "message" to JsonPrimitive(message))),
)

private object ServiceEvidenceTool : SimpleTool<ServiceEvidenceTool.Args>(
    argsType = typeToken<Args>(),
    name = "lookup_service_evidence",
    description = "Returns bounded sample operational evidence for one service.",
) {
    @Serializable
    data class Args(val service: String)

    override suspend fun execute(args: Args): String =
        "Service `${args.service}` has elevated latency correlated with a recent deployment."
}

@Serializable
private data class StartWorkspaceRunRequest(val service: String, val runId: String? = null)

@Serializable
private data class StartWorkspaceRunResponse(val runId: String)

@Serializable
private data class QueueGuidanceRequest(val guidance: String)
