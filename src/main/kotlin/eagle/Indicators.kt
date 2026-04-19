package eagle

/**
 * One step of an exponential moving average.
 *
 * `k = 2 / (period + 1)` — first value seeds with the close itself.
 */
fun ema(price: Double, period: Int, previous: Double?): Double {
    if (previous == null) return price
    val k = 2.0 / (period + 1)
    return price * k + previous * (1.0 - k)
}
