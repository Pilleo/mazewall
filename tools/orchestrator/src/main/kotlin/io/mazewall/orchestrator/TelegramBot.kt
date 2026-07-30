package io.mazewall.orchestrator

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null
)

@Serializable
data class TelegramUpdate(
    val update_id: Long,
    val message: TelegramMessage? = null,
    val callback_query: CallbackQuery? = null
)

@Serializable
data class TelegramMessage(
    val message_id: Long,
    val text: String? = null
)

@Serializable
data class CallbackQuery(
    val id: String,
    val data: String? = null
)

@Serializable
data class InlineKeyboardButton(
    val text: String,
    val callback_data: String
)

@Serializable
data class ReplyMarkup(
    val inline_keyboard: List<List<InlineKeyboardButton>>
)

@Serializable
data class SendMessageRequest(
    val chat_id: String,
    val text: String,
    val parse_mode: String = "Markdown",
    val reply_markup: ReplyMarkup? = null
)

@Serializable
data class AnswerCallbackQueryRequest(
    val callback_query_id: String
)

class TelegramBot(
    private val botToken: String,
    private val chatId: String,
    private val transport: HttpTransport = RealHttpTransport(HttpClient.newHttpClient())
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastUpdateId = 0L
    var onReviewRequested: ((focusComments: String) -> Unit)? = null

    init {
        // Run a fast getUpdates to find the current offset so we don't process historical alerts
        initializeOffset()
    }

    private fun initializeOffset() {
        try {
            val url = "https://api.telegram.org/bot$botToken/getUpdates?limit=1"
            val responseText = get(url)
            if (responseText != null) {
                val updatesResponse = json.decodeFromString<TelegramResponse<List<TelegramUpdate>>>(responseText)
                if (updatesResponse.ok && !updatesResponse.result.isNullOrEmpty()) {
                    lastUpdateId = updatesResponse.result.maxOf { it.update_id } + 1
                    println("Telegram Bot initialized. Current offset: $lastUpdateId")
                }
            }
        } catch (e: Exception) {
            System.err.println("Warning: Failed to initialize Telegram offset: ${e.message}")
        }
    }

    fun pollUpdates() {
        try {
            val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$lastUpdateId&timeout=0"
            val responseText = get(url) ?: return
            val updatesResponse = json.decodeFromString<TelegramResponse<List<TelegramUpdate>>>(responseText)
            if (updatesResponse.ok && updatesResponse.result != null) {
                for (update in updatesResponse.result) {
                    lastUpdateId = update.update_id + 1
                    val callbackQuery = update.callback_query
                    if (callbackQuery?.data != null) {
                        val data = callbackQuery.data
                        if (data.startsWith("review")) {
                            answerCallback(callbackQuery.id)
                            handleReviewCallback()
                        }
                    } else if (update.message?.text != null) {
                        val text = update.message.text.trim()
                        if (text.startsWith("/review")) {
                            val comments = text.removePrefix("/review").trim().ifEmpty { "default" }
                            onReviewRequested?.invoke(comments)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun sendMessage(text: String, includeReviewButton: Boolean = false) {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val markup = if (includeReviewButton) {
            ReplyMarkup(
                inline_keyboard = listOf(
                    listOf(InlineKeyboardButton(text = "🔍 Review", callback_data = "review"))
                )
            )
        } else null

        val payload = SendMessageRequest(chat_id = chatId, text = text, reply_markup = markup)
        post(url, json.encodeToString(SendMessageRequest.serializer(), payload))
    }

    fun sendMessageWithApprovalMarkup(issueId: String, text: String) {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val markup = ReplyMarkup(
            inline_keyboard = listOf(
                listOf(
                    InlineKeyboardButton(text = "✅ Approve", callback_data = "approve:$issueId"),
                    InlineKeyboardButton(text = "⏭️ Skip", callback_data = "skip:$issueId"),
                    InlineKeyboardButton(text = "🔍 Review", callback_data = "review:$issueId")
                )
            )
        )
        val payload = SendMessageRequest(chat_id = chatId, text = text, reply_markup = markup)
        post(url, json.encodeToString(SendMessageRequest.serializer(), payload))
    }

    fun checkApprovalNonBlocking(issueId: String): Boolean? {
        try {
            val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$lastUpdateId&timeout=0"
            val responseText = get(url) ?: return null
            val updatesResponse = json.decodeFromString<TelegramResponse<List<TelegramUpdate>>>(responseText)
            if (updatesResponse.ok && updatesResponse.result != null) {
                for (update in updatesResponse.result) {
                    lastUpdateId = update.update_id + 1
                    val callbackQuery = update.callback_query
                    if (callbackQuery != null && callbackQuery.data != null) {
                        val data = callbackQuery.data
                        if (data == "approve:$issueId") {
                            answerCallback(callbackQuery.id)
                            return true
                        } else if (data == "skip:$issueId") {
                            answerCallback(callbackQuery.id)
                            return false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error checking approval non-blocking: ${e.message}")
        }
        return null
    }

    fun waitForApproval(issueId: String): Boolean {
        println("⏳ Waiting for user approval on Telegram for $issueId...")
        while (true) {
            val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$lastUpdateId&timeout=30"
            val responseText = get(url)
            if (responseText == null) {
                Thread.sleep(5000)
                continue
            }
            try {
                // Parse updates using the generic serializer wrapper
                val updatesResponse = json.decodeFromString<TelegramResponse<List<TelegramUpdate>>>(responseText)
                if (updatesResponse.ok && updatesResponse.result != null) {
                    for (update in updatesResponse.result) {
                        lastUpdateId = update.update_id + 1
                        val callbackQuery = update.callback_query
                        if (callbackQuery != null && callbackQuery.data != null) {
                            val data = callbackQuery.data
                            if (data == "approve:$issueId") {
                                answerCallback(callbackQuery.id)
                                return true
                            } else if (data == "skip:$issueId") {
                                answerCallback(callbackQuery.id)
                                return false
                            } else if (data.startsWith("review")) {
                                answerCallback(callbackQuery.id)
                                val launched = handleReviewCallback()
                                if (launched) {
                                    // Review task launched. Postpone current task so orchestrator can execute review task.
                                    return false
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Error parsing Telegram updates: ${e.message}")
            }
            Thread.sleep(1000)
        }
    }

    private fun handleReviewCallback(): Boolean {
        sendMessage("💬 *Review Task Focus Prompt*\n\nPlease reply with specific areas or comments you want Jules to focus on during this review (or type 'all'):", includeReviewButton = false)
        val focusComments = waitForUserTextMessage()
        if (focusComments.isBlank()) return false
        onReviewRequested?.invoke(focusComments)
        return true
    }

    fun waitForUserTextMessage(): String {
        println("⏳ Waiting for user text reply on Telegram...")
        while (true) {
            val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$lastUpdateId&timeout=30"
            val responseText = get(url)
            if (responseText != null) {
                try {
                    val updatesResponse = json.decodeFromString<TelegramResponse<List<TelegramUpdate>>>(responseText)
                    if (updatesResponse.ok && updatesResponse.result != null) {
                        for (update in updatesResponse.result) {
                            lastUpdateId = update.update_id + 1
                            val text = update.message?.text
                            if (!text.isNullOrBlank()) {
                                return text.trim()
                            }
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("Error reading Telegram text reply: ${e.message}")
                }
            }
            Thread.sleep(1000)
        }
    }

    private fun answerCallback(callbackQueryId: String) {
        val url = "https://api.telegram.org/bot$botToken/answerCallbackQuery"
        val payload = AnswerCallbackQueryRequest(callback_query_id = callbackQueryId)
        post(url, json.encodeToString(AnswerCallbackQueryRequest.serializer(), payload))
    }

    private fun post(url: String, jsonBody: String): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        return try {
            val response = transport.send(request)
            if (response.statusCode() !in 200..299) {
                System.err.println("Telegram POST to $url returned status code ${response.statusCode()}: ${response.body()}")
            }
            response.body()
        } catch (e: Exception) {
            System.err.println("HTTP POST to $url failed: ${e.message}")
            null
        }
    }

    private fun get(url: String): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()
        return try {
            val response = transport.send(request)
            response.body()
        } catch (e: Exception) {
            System.err.println("HTTP GET to $url failed: ${e.message}")
            null
        }
    }
}
