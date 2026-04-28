package eagle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NotifyMode {
    @SerialName("telegram")
    TELEGRAM,

    @SerialName("web")
    WEB
}

@Serializable
data class AppConfig(
    /**
     * `telegram` sends alerts to Telegram; `web` serves a local dashboard only
     * (no bot token required).
     */
    val mode: NotifyMode = NotifyMode.TELEGRAM,
    val web: WebDashboardConfig = WebDashboardConfig(),
    val binance: BinanceConfig = BinanceConfig(),
    val instances: List<InstanceConfig>
)

@Serializable
data class WebDashboardConfig(
    /** Bind address (default loopback). */
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    /** Max alerts kept in memory for the dashboard / API. */
    val maxAlerts: Int = 500
)

@Serializable
data class BinanceConfig(
    val restUrl: String = "https://api.binance.com",
    val wsUrl: String = "wss://stream.binance.com:9443/stream",
    /** How many USDT pairs to follow (by 24h quote volume). */
    val topPairs: Int = 80
)

@Serializable
data class InstanceConfig(
    /** Shown in logs and in alert headers. */
    val name: String,
    /** Required when [AppConfig.mode] is [NotifyMode.TELEGRAM]. */
    val telegramChatId: String? = null,
    /** Forum topic id for supergroups; omit or null for the main chat. */
    val telegramTopicId: String? = null,
    /** Binance kline interval, e.g. 5m, 1h, 4h, 1d. */
    val timeframe: String = "5m",
    /**
     * Telegram message language: `en`, `es`, or `zh` (Simplified Chinese).
     * Aliases: `english`, `spanish`, `zh-cn`, `chinese`, …
     */
    val language: String = "en",
    /** Each pair raises an alert when the fast EMA crosses the slow one on a closed candle. */
    val emaCrosses: List<EmaCrossConfig>
)

@Serializable
data class EmaCrossConfig(
    val fast: Int,
    val slow: Int
)
