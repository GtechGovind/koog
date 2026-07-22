package ai.koog.agents.example.workspace

import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.agents.workspace.AgentWorkspaceController
import ai.koog.agents.workspace.model.AgentInputResponse
import ai.koog.agents.workspace.model.AgentWorkspaceRunOutcome
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

class AgentWorkspaceServerTest {
    @Test
    fun `workspace run resumes after rebuilding all process state`() = runTest {
        val root = createTempDirectory("koog-workspace-example-")
        val runId = "restart-safe-run"
        val checkpointStore = JVMFilePersistenceStorageProvider(root.resolve("checkpoints"))
        val executor = simpleOllamaAIExecutor()
        try {
            val firstStore = JvmJsonAgentWorkspaceStore(root.resolve("workspace.json"))
            val firstController = AgentWorkspaceController(firstStore)
            firstStore.saveInput(runId, "checkout")

            assertIs<AgentWorkspaceRunOutcome.Suspended>(
                firstController.runAgent(
                    runId,
                    createAgent(firstController, firstStore, checkpointStore, executor),
                    "checkout",
                )
            )

            val restartedStore = JvmJsonAgentWorkspaceStore(root.resolve("workspace.json"))
            val restartedController = AgentWorkspaceController(restartedStore)
            val interruption = requireNotNull(restartedController.snapshot(runId)?.interruption)
            restartedController.answer(
                runId,
                AgentInputResponse(
                    requestId = interruption.request.id,
                    selectedOptionIds = listOf("approve"),
                    respondedAt = Clock.System.now().toString(),
                ),
            )
            restartedController.revalidateDecision(runId)
            restartedStore.enqueueGuidance(runId, "Compare the deployment timestamp with the latency spike.")
            val checkpoint = requireNotNull(
                checkpointStore.getCheckpoints(runId)
                    .firstOrNull { it.checkpointId == interruption.checkpointId }
            )

            assertIs<AgentWorkspaceRunOutcome.Completed<String>>(
                restartedController.resumeAgent(
                    runId,
                    createAgent(restartedController, restartedStore, checkpointStore, executor),
                    requireNotNull(restartedStore.loadInput(runId)),
                    checkpoint,
                )
            )
            val contentKinds = restartedController.events(runId)
                .filter { it.type == "content.created" }
                .mapNotNull { it.data["content"]?.toString() }
            assertTrue(contentKinds.any { "markdown" in it })
            assertTrue(contentKinds.any { "artifact_reference" in it })
            assertTrue(contentKinds.any { "deployment timestamp" in it })
        } finally {
            executor.close()
            root.toFile().deleteRecursively()
        }
    }
}
