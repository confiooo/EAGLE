package eagle

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("Main")
private val jsonParser = Json { ignoreUnknownKeys = true }

fun main() = runBlocking {
    val env = dotenv { ignoreIfMissing = true }
    val configFile = File("config.json")
    if (!configFile.exists()) {
        error(
            "config.json not found in the working directory. " +
                "Copy config.example.json and adjust it for your chats:\n" +
                "  cp config.example.json config.json"
        )
    }

    val config: AppConfig = jsonParser.decodeFromString(configFile.readText())
    require(config.instances.isNotEmpty()) { "config.json must define at least one instance" }
    config.instances.forEach { it.locale() }

    val botToken = env["TELEGRAM_BOT_TOKEN"]?.takeIf { it.isNotBlank() }
    when (config.mode) {
        NotifyMode.TELEGRAM -> {
            require(!botToken.isNullOrBlank()) {
                "Missing TELEGRAM_BOT_TOKEN. Add it to a .env file (see .env.example) " +
                    "or export it in your environment."
            }
            config.instances.forEach { inst ->
                require(!inst.telegramChatId.isNullOrBlank()) {
                    "Instance \"${inst.name}\" needs telegramChatId when mode is \"telegram\""
                }
            }
        }
        NotifyMode.WEB -> {
            require(config.web.port in 1..65535) { "web.port must be between 1 and 65535" }
            require(config.web.maxAlerts >= 1) { "web.maxAlerts must be at least 1" }
        }
    }

    log.info("Mode: {}", config.mode)
    log.info("Starting Eagle with ${config.instances.size} instance(s):")
    config.instances.forEach { inst ->
        val crosses = inst.emaCrosses.joinToString(", ") { "EMA${it.fast}/EMA${it.slow}" }
        log.info("  • [{}] {} | {}", inst.name, inst.timeframe, crosses)
    }

    val alertHub = if (config.mode == NotifyMode.WEB) AlertHub(config.web.maxAlerts) else null
    if (alertHub != null) {
        startWebDashboard(config.web.host, config.web.port, alertHub, jsonParser)
    }

    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
        install(ContentNegotiation) { json(jsonParser) }
        install(WebSockets)
    }

    val symbols = fetchTopUsdtSymbols(client, config.binance.restUrl, config.binance.topPairs)
    log.info("Watching {} pairs (showing first 12): {} …", symbols.size, symbols.take(12).joinToString())

    val tokenForTelegram = botToken

    if (config.mode == NotifyMode.TELEGRAM) {
        val token = tokenForTelegram!!
        config.instances.forEach { inst ->
            val loc = inst.locale()
            val crosses = inst.emaCrosses.joinToString(" | ") { "EMA${it.fast}/EMA${it.slow}" }
            val safeName = inst.name.escapeTelegramHtml()
            val msg = buildString {
                appendLine("🚀 <b>${loc.startupHeadline()}</b> — <i>$safeName</i>")
                appendLine("• ${loc.labelTimeframe()}: <b>${inst.timeframe}</b>")
                appendLine("• ${loc.labelCrosses()}: <b>$crosses</b>")
                append("• ${loc.labelPairs()}: <b>${symbols.size}</b>")
            }
            sendTelegram(client, token, inst.telegramChatId!!, inst.telegramTopicId, msg)
        }
    }

    val onAlert: suspend (InstanceConfig, String, AlertMeta) -> Unit = when (config.mode) {
        NotifyMode.TELEGRAM -> { inst, html, _ ->
            sendTelegram(
                client,
                tokenForTelegram!!,
                inst.telegramChatId!!,
                inst.telegramTopicId,
                html
            )
        }
        NotifyMode.WEB -> { inst, html, meta ->
            alertHub!!.publish(inst, meta, html)
        }
    }

    monitor(
        client = client,
        symbols = symbols,
        wsBase = config.binance.wsUrl,
        instances = config.instances,
        onAlert = onAlert
    )
}
