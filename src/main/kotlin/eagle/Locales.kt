package eagle

/**
 * Locales for Telegram copy. Slf4j logs stay English.
 *
 * Codes: `en`, `es`, `zh` (Simplified Chinese). See [parse].
 */
enum class AppLocale {
    EN,
    ES,
    ZH;

    companion object {
        /** Accepts `en` / `es` / `zh` and common aliases (e.g. `zh-cn`, `spanish`). */
        fun parse(raw: String?): AppLocale {
            if (raw.isNullOrBlank()) return EN
            return when (raw.lowercase().trim().replace('_', '-')) {
                "en", "english" -> EN
                "es", "español", "espanol", "spanish" -> ES
                "zh", "zh-cn", "zhcn", "chinese", "cn", "mandarin" -> ZH
                else -> throw IllegalArgumentException(
                    "unknown language \"$raw\" (use en, es, or zh)"
                )
            }
        }
    }

    fun startupHeadline(): String = when (this) {
        EN -> "Eagle started"
        ES -> "Eagle iniciado"
        ZH -> "Eagle 已启动"
    }

    fun labelTimeframe(): String = when (this) {
        EN -> "Timeframe"
        ES -> "Marco temporal"
        ZH -> "周期"
    }

    fun labelCrosses(): String = when (this) {
        EN -> "Crosses"
        ES -> "Cruces"
        ZH -> "均线组合"
    }

    fun labelPairs(): String = when (this) {
        EN -> "Pairs"
        ES -> "Pares"
        ZH -> "交易对"
    }

    fun alertBullishCross(): String = when (this) {
        EN -> "Bullish cross"
        ES -> "Cruce alcista"
        ZH -> "看涨交叉"
    }

    fun alertBearishCross(): String = when (this) {
        EN -> "Bearish cross"
        ES -> "Cruce bajista"
        ZH -> "看跌交叉"
    }

    /** One sentence: fast EMA crossed above/below slow EMA. */
    fun emaCrossLine(fast: Int, slow: Int, bullish: Boolean): String = when (this) {
        EN -> if (bullish) {
            "EMA$fast crossed <b>above</b> EMA$slow"
        } else {
            "EMA$fast crossed <b>below</b> EMA$slow"
        }
        ES -> if (bullish) {
            "EMA$fast cruzó <b>por encima</b> de EMA$slow"
        } else {
            "EMA$fast cruzó <b>por debajo</b> de EMA$slow"
        }
        ZH -> if (bullish) {
            "EMA$fast <b>上穿</b> EMA$slow"
        } else {
            "EMA$fast <b>下穿</b> EMA$slow"
        }
    }

    fun emaValueLine(period: Int, formatted: String): String = when (this) {
        EN -> "EMA$period: <b>$formatted</b>"
        ES -> "EMA$period: <b>$formatted</b>"
        ZH -> "EMA$period：<b>$formatted</b>"
    }

    fun closeLine(formatted: String): String = when (this) {
        EN -> "Close: <b>$formatted</b>"
        ES -> "Cierre: <b>$formatted</b>"
        ZH -> "收盘价：<b>$formatted</b>"
    }
}

fun InstanceConfig.locale(): AppLocale =
    try {
        AppLocale.parse(language)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("instance \"${name}\": ${e.message}", e)
    }
