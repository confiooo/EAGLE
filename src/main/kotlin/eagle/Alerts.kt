package eagle

import kotlinx.serialization.Serializable

/** Metadata for one EMA cross alert (shared by Telegram and web dashboard). */
data class AlertMeta(
    val symbol: String,
    val timeframe: String,
    val bullish: Boolean,
    val emaFast: Int,
    val emaSlow: Int,
    val fastEma: Double,
    val slowEma: Double,
    val close: Double
)

@Serializable
data class DashboardAlert(
    val id: Long,
    val timestampMillis: Long,
    val instanceName: String,
    val symbol: String,
    val timeframe: String,
    val bullish: Boolean,
    val emaFast: Int,
    val emaSlow: Int,
    val fastEma: Double,
    val slowEma: Double,
    val close: Double,
    val messageHtml: String
)
