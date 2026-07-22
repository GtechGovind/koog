package ai.koog.agents.example.workspace

import ai.koog.agents.workspace.AgentWorkspaceStore
import ai.koog.agents.workspace.model.AgentWorkspaceEvent
import ai.koog.agents.workspace.model.AgentWorkspaceRunSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Minimal JSON store used only by the runnable workspace example. */
internal class JvmJsonAgentWorkspaceStore(
    private val file: Path,
    private val json: Json = Json { encodeDefaults = true; prettyPrint = true },
) : AgentWorkspaceStore {
    private val mutex = Mutex()
    private var state = load()

    override suspend fun createRun(snapshot: AgentWorkspaceRunSnapshot): Boolean = mutate { current ->
        if (snapshot.runId in current.runs) return@mutate false
        state = current.copy(runs = current.runs + (snapshot.runId to snapshot))
        true
    }

    override suspend fun loadRun(runId: String): AgentWorkspaceRunSnapshot? = mutex.withLock {
        state.runs[runId]
    }

    override suspend fun compareAndSetRun(
        expectedRevision: Long,
        snapshot: AgentWorkspaceRunSnapshot,
    ): Boolean = mutate { current ->
        val existing = current.runs[snapshot.runId] ?: return@mutate false
        if (existing.revision != expectedRevision || snapshot.revision != expectedRevision + 1) return@mutate false
        state = current.copy(runs = current.runs + (snapshot.runId to snapshot))
        true
    }

    override suspend fun appendEvent(event: AgentWorkspaceEvent): AgentWorkspaceEvent = mutate { current ->
        val runEvents = current.events[event.runId].orEmpty()
        val stored = event.copy(sequence = (runEvents.lastOrNull()?.sequence ?: 0L) + 1L)
        state = current.copy(events = current.events + (event.runId to (runEvents + stored)))
        stored
    }

    override suspend fun listEvents(runId: String, afterSequence: Long): List<AgentWorkspaceEvent> = mutex.withLock {
        state.events[runId].orEmpty().filter { it.sequence > afterSequence }
    }

    suspend fun saveInput(runId: String, input: String) = mutate { current ->
        state = current.copy(inputs = current.inputs + (runId to input))
    }

    suspend fun loadInput(runId: String): String? = mutex.withLock { state.inputs[runId] }

    suspend fun enqueueGuidance(runId: String, guidance: String) = mutate { current ->
        val queued = current.guidance[runId].orEmpty() + guidance
        state = current.copy(guidance = current.guidance + (runId to queued))
    }

    suspend fun loadGuidance(runId: String): List<String> = mutex.withLock { state.guidance[runId].orEmpty() }

    private suspend fun <T> mutate(block: (State) -> T): T = mutex.withLock {
        val result = block(state)
        persist()
        result
    }

    private fun load(): State {
        if (!Files.isRegularFile(file)) return State()
        return json.decodeFromString(State.serializer(), Files.readString(file))
    }

    private fun persist() {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(State.serializer(), state))
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Serializable
    private data class State(
        val runs: Map<String, AgentWorkspaceRunSnapshot> = emptyMap(),
        val events: Map<String, List<AgentWorkspaceEvent>> = emptyMap(),
        val inputs: Map<String, String> = emptyMap(),
        val guidance: Map<String, List<String>> = emptyMap(),
    )
}
