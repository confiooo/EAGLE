package eagle

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Binance")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Top [limit] USDT spot pairs by 24h quote volume (descending).
 * Skips symbols whose names look like leveraged tokens (`…UPUSDT` / `…DOWNUSDT`).
 */
suspend fun fetchTopUsdtSymbols(client: HttpClient, restUrl: String, limit: Int): List<String> {
    log.info("Loading exchange info…")
    val infoText: String = client.get("$restUrl/api/v3/exchangeInfo").body()
    val infoRoot = json.parseToJsonElement(infoText).jsonObject
    val symbolsArray = infoRoot["symbols"]?.jsonArray
        ?: error("Binance exchangeInfo response has no symbols array")

    val trading = symbolsArray
        .map { it.jsonObject }
        .filter { it["status"]?.jsonPrimitive?.content == "TRADING" }
        .mapNotNull { it["symbol"]?.jsonPrimitive?.content }
        .toSet()

    log.info("Loading 24h tickers…")
    val tickerText: String = client.get("$restUrl/api/v3/ticker/24hr").body()
    val tickers = json.parseToJsonElement(tickerText).jsonArray

    return tickers
        .map { it.jsonObject }
        .filter { obj ->
            val sym = obj["symbol"]?.jsonPrimitive?.content ?: return@filter false
            sym.endsWith("USDT") &&
                !sym.contains("UPUSDT") &&
                !sym.contains("DOWNUSDT") &&
                sym in trading
        }
        .mapNotNull { obj ->
            val sym = obj["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val vol = obj["quoteVolume"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            sym to vol
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
}
