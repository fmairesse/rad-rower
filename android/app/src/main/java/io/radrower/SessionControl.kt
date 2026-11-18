package io.radrower

/** Rower session states; other codes reported by the rower are treated as unavailable. */
internal enum class SessionState(val code: Int) {
    IDLE(1),
    IN_PROGRESS(2),
    PAUSED(3),
    FINISHED(4);

    companion object {
        fun fromCode(code: Int?): SessionState? = entries.firstOrNull { it.code == code }
    }
}

internal data class CommandStep(
    val messages: List<List<ByteArray>>,
    val expectedState: SessionState,
    val required: Boolean = true,
)

internal fun commandSteps(state: SessionState?, stop: Boolean): List<CommandStep>? =
    when {
        stop && state == SessionState.IN_PROGRESS -> listOf(
            CommandStep(RowerProtocol.pause, SessionState.PAUSED),
            CommandStep(RowerProtocol.end, SessionState.FINISHED, required = false),
            CommandStep(RowerProtocol.idle, SessionState.IDLE),
        )
        stop && state == SessionState.PAUSED -> listOf(
            CommandStep(RowerProtocol.end, SessionState.FINISHED, required = false),
            CommandStep(RowerProtocol.idle, SessionState.IDLE),
        )
        !stop && state == SessionState.IN_PROGRESS -> listOf(CommandStep(RowerProtocol.pause, SessionState.PAUSED))
        !stop && state == SessionState.IDLE || state == SessionState.PAUSED -> listOf(CommandStep(RowerProtocol.start, SessionState.IN_PROGRESS))
        else -> null
    }

internal suspend fun runCommandSteps(
    steps: List<CommandStep>,
    send: suspend (CommandStep) -> Unit,
) {
    for (step in steps) send(step)
}
