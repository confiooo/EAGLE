package eagle

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Monitor")
private val json = Json { ignoreUnknownKeys = true }

/** One fast/slow pair for a single symbol within one instance. */
private data class CrossState(
    val fast: Int,
    val slow: Int,
    var prevFastEma: Double? = null,
    var prevSlowEma: Double? = null,
    /** +1 fast above slow, -1 below, 0 flat / unknown */
    var prevRelation: Int = 0
)

private class SymbolState(crosses: List<EmaCrossConfig>) {
    val crossStates: List<CrossState> = crosses.map { CrossState(it.fast, it.slow) }
}

private class InstanceState(val config: InstanceConfig) {
    private val bySymbol = mutableMapOf<String, SymbolState>()

    fun stateFor(symbol: String) =
        bySymbol.getOrPut(symbol) { SymbolState(config.emaCrosses) }
}

/**
 * Binance combined stream: one stream lists every `symbol@kline_interval` channel.
 * We open one WebSocket per distinct timeframe so instances that share a timeframe
 * reuse the same connection.
 */
private fun combinedStreamUrl(wsBase: String, symbols: List<String>, timeframe: String): String {
    val streams = symbols.joinToString("/") { "${it.lowercase()}@kline_$timeframe" }
    return "$wsBase?streams=$streams"
}

/**
 * Spawns one coroutine per unique timeframe. Each loop reconnects with backoff if the socket drops.
 */
suspend fun monitor(
    client: HttpClient,
    symbols: List<String>,
    wsBase: String,
    instances: List<InstanceConfig>,
    onAlert: suspend (InstanceConfig, String, AlertMeta) -> Unit
) = coroutineScope {
    val byTimeframe = instances.groupBy { it.timeframe }
    byTimeframe.forEach { (timeframe, group) ->
        launch {
            runTimeframeLoop(client, symbols, wsBase, timeframe, group, onAlert)
        }
    }
}

private fun crossAlertText(
    loc: AppLocale,
    symbol: String,
    timeframe: String,
    instanceName: String,
    cross: CrossState,
    bullish: Boolean,
    fastEma: Double,
    slowEma: Double,
    close: Double
): String {
    val emoji = if (bullish) "🟢" else "🔴"
    val name = instanceName.escapeTelegramHtml()
    val f1 = "%.6f".format(fastEma)
    val f2 = "%.6f".format(slowEma)
    val fc = "%.6f".format(close)
    val direction = if (bullish) loc.alertBullishCross() else loc.alertBearishCross()
    return buildString {
        appendLine("$emoji <b>$symbol</b> ($timeframe) — <i>$name</i>")
        appendLine("<b>$direction</b>")
        appendLine("• ${loc.emaCrossLine(cross.fast, cross.slow, bullish)}")
        appendLine("• ${loc.emaValueLine(cross.fast, f1)}")
        appendLine("• ${loc.emaValueLine(cross.slow, f2)}")
        append("• ${loc.closeLine(fc)}")
    }
}

private suspend fun runTimeframeLoop(
    client: HttpClient,
    symbols: List<String>,
    wsBase: String,
    timeframe: String,
    instances: List<InstanceConfig>,
    onAlert: suspend (InstanceConfig, String, AlertMeta) -> Unit
) {
    val url = combinedStreamUrl(wsBase, symbols, timeframe)
    val instanceStates = instances.map { InstanceState(it) }
    var backoffMs = 1_000L

    while (true) {
        try {
            client.webSocket(url) {
                log.info("[{}] connected — {} pairs, {} instance(s)", timeframe, symbols.size, instances.size)
                backoffMs = 1_000L

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue

                    val root = json.parseToJsonElement(frame.readText()).jsonObject
                    val payload = root["data"]?.jsonObject ?: continue
                    val symbol = payload["s"]?.jsonPrimitive?.content ?: continue
                    val k = payload["k"]?.jsonObject ?: continue

                    val candleClosed = k["x"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (!candleClosed) continue

                    val close = k["c"]?.jsonPrimitive?.doubleOrNull ?: continue

                    for (inst in instanceStates) {
                        val symState = inst.stateFor(symbol)
                        for (cross in symState.crossStates) {
                            val fastEma = ema(close, cross.fast, cross.prevFastEma)
                            val slowEma = ema(close, cross.slow, cross.prevSlowEma)

                            val relation = when {
                                fastEma > slowEma -> 1
                                fastEma < slowEma -> -1
                                else -> 0
                            }

                            val crossed = cross.prevRelation != 0 &&
                                relation != 0 &&
                                relation != cross.prevRelation

                            if (crossed) {
                                val bullish = relation == 1
                                val loc = inst.config.locale()
                                val msg = crossAlertText(
                                    loc = loc,
                                    symbol = symbol,
                                    timeframe = timeframe,
                                    instanceName = inst.config.name,
                                    cross = cross,
                                    bullish = bullish,
                                    fastEma = fastEma,
                                    slowEma = slowEma,
                                    close = close
                                )
                                val meta = AlertMeta(
                                    symbol = symbol,
                                    timeframe = timeframe,
                                    bullish = bullish,
                                    emaFast = cross.fast,
                                    emaSlow = cross.slow,
                                    fastEma = fastEma,
                                    slowEma = slowEma,
                                    close = close
                                )

                                log.info(
                                    "[{}] {} {} EMA{}/EMA{}",
                                    inst.config.name,
                                    symbol,
                                    if (bullish) "bullish" else "bearish",
                                    cross.fast,
                                    cross.slow
                                )
                                onAlert(inst.config, msg, meta)
                            }

                            cross.prevFastEma = fastEma
                            cross.prevSlowEma = slowEma
                            cross.prevRelation = relation
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "[{}] socket closed ({}). Retrying in {}s…",
                timeframe,
                e.message ?: e::class.simpleName,
                backoffMs / 1000
            )
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 60_000L)
        }
    }
}
