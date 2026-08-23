package io.mazewall.orchestrator

fun interface LinePrompt {
    fun ask(question: String, default: String?): String
}

class ConsoleLinePrompt : LinePrompt {
    override fun ask(question: String, default: String?): String {
        val suffix = if (default != null) " [$default]" else ""
        print("$question$suffix: ")
        System.out.flush()
        val line = readlnOrNull()?.trim().orEmpty()
        return line.ifEmpty { default.orEmpty() }
    }
}

object IssueInterview {
    fun complete(request: IssueScaffoldRequest, prompt: LinePrompt, askOpenQuestions: Boolean, askKernel: Boolean): IssueScaffoldRequest {
        var next = request
        if (askKernel) {
            val answer = prompt.ask("Need kernel/seccomp/Landlock integration tests (needs_kernel)", "n")
            next = next.copy(needsKernel = isYes(answer))
        }
        if (askOpenQuestions) {
            val answer = prompt.ask("Are there open questions a human must answer before work starts", "n")
            if (isYes(answer)) {
                val questions = mutableListOf<String>()
                while (true) {
                    val q = prompt.ask("Open question (empty line to finish)", "")
                    if (q.isBlank()) break
                    questions += q
                }
                next = next.copy(openQuestionItems = questions)
            }
        }
        if (next.contextBody.isNullOrBlank()) {
            val context = prompt.ask("Context (empty keeps FILL for later)", "")
            if (context.isNotBlank()) next = next.copy(contextBody = context)
        }
        if (next.neededBody.isNullOrBlank()) {
            val needed = prompt.ask("Needed (empty keeps FILL for later)", "")
            if (needed.isNotBlank()) next = next.copy(neededBody = needed)
        }
        return next
    }

    private fun isYes(raw: String): Boolean {
        val token = raw.trim().lowercase()
        return token == "y" || token == "yes" || token == "true"
    }
}
