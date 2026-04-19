package eagle

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Telegram")

/** Telegram [HTML mode](https://core.telegram.org/bots/api#html-style) requires escaping these in user text. */
internal fun String.escapeTelegramHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

suspend fun sendTelegram(
    client: HttpClient,
    botToken: String,
    chatId: String,
    topicId: String?,
    text: String
) {
    val url = "https://api.telegram.org/bot$botToken/sendMessage"
    val body = buildMap<String, Any?> {
        put("chat_id", chatId.toLongOrNull() ?: chatId)
        if (!topicId.isNullOrBlank()) {
            put("message_thread_id", topicId.trim().toLongOrNull() ?: topicId)
        }
        put("text", text)
        put("parse_mode", "HTML")
        put("disable_web_page_preview", true)
    }
    try {
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(mapToJsonBody(body))
        }
        if (response.status != HttpStatusCode.OK) {
            log.error("Telegram HTTP {}: {}", response.status, response.bodyAsText())
        }
    } catch (e: Exception) {
        log.error("Could not send Telegram message: {}", e.message)
    }
}

/** Tiny JSON encoder so we do not pull in another serializer just for this POST body. */
private fun mapToJsonBody(map: Map<String, Any?>): String = buildString {
    append('{')
    map.entries.forEachIndexed { i, (key, value) ->
        if (i > 0) append(',')
        append('"').append(key).append("\":")
        when (value) {
            is String -> append('"')
                .append(
                    value
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                )
                .append('"')
            is Boolean -> append(value)
            is Number -> append(value)
            null -> append("null")
            else -> append('"').append(value.toString()).append('"')
        }
    }
    append('}')
}
