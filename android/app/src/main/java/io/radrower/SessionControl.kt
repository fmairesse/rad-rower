package io.radrower

internal data class CommandStep(
    val messages: List<List<ByteArray>>,
    val expectedState: Int,
    val required: Boolean = true,
)

internal fun commandSteps(state: Int?, stop: Boolean): List<CommandStep>? =
    when {
        stop && state == 2 -> listOf(
            CommandStep(RowerProtocol.pause, 3),
            CommandStep(RowerProtocol.end, 4, required = false),
            CommandStep(RowerProtocol.idle, 1),
        )
        stop && state == 3 -> listOf(
            CommandStep(RowerProtocol.end, 4, required = false),
            CommandStep(RowerProtocol.idle, 1),
        )
        !stop && state == 2 -> listOf(CommandStep(RowerProtocol.pause, 3))
        !stop && state in listOf(1, 3) -> listOf(CommandStep(RowerProtocol.start, 2))
        else -> null
    }

internal suspend fun runCommandSteps(
    steps: List<CommandStep>,
    send: suspend (CommandStep) -> Unit,
) {
    for (step in steps) send(step)
}
