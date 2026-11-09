package com.ljyh.mei.playback

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.random.Random

/** Zero-bias ordering used by the official Android 9.6.05 client. */
internal object OfficialMobileShuffle {
    const val PARENT_EXPERIMENT = "RandomPlaylist_Adjustment_Android"
    private const val TWO_YEARS_MS = 63_072_000_000L

    enum class Strategy { DEFAULT, PURE, T1_C, T1_T1, T1_T2, T2_C, T2_T1, T2_T2, T3_C, T3_T1, T3_T2, T4 }
    data class Parameters(
        val a: Double, val b: Double, val c: Double, val d: Double, val e: Double,
        val maxFetchPlayRecordCount: Double = 10_000.0, val subgroupSize: Double = 20.0,
    )
    data class State(var strategy: Strategy, val params: Parameters?)
    data class HistoryRecord(val playTimes: Double, val toLastPlayDays: Double?)

    fun resolve(parent: JsonObject?, child: JsonObject?): State {
        val parentData = envelopeData(parent) ?: return State(Strategy.DEFAULT, null)
        val assignment = parentData.get(PARENT_EXPERIMENT)?.objectOrNull()
        val group = assignment?.get("expGroupName")?.stringOrNull() ?: return State(Strategy.DEFAULT, null)
        val defaults = when (group) {
            "t1" -> Parameters(.3, .5, .15, 3.0, .1)
            "t2" -> Parameters(.35, .5, .05, 3.0, .1)
            "t3" -> Parameters(.3, .5, 0.0, 3.0, 1.0)
            "t4" -> Parameters(0.0, 0.0, 0.0, 0.0, 0.0)
            else -> return State(Strategy.DEFAULT, null)
        }
        val config = assignment?.get("clientConfig")?.objectOrNull() ?: JsonObject()
        val params = defaults.copy(
            a = if (group == "t4") 0.0 else config.numberOrDefault("a0", defaults.a),
            b = if (group == "t4") 0.0 else config.numberOrDefault("b0", defaults.b),
            c = if (group == "t3" || group == "t4") defaults.c else config.numberOrDefault("c0", defaults.c),
            d = if (group == "t4") 0.0 else config.numberOrDefault("d0", defaults.d, integer = true),
            e = if (group == "t4") 0.0 else config.numberOrDefault("e0", defaults.e),
            maxFetchPlayRecordCount = config.numberOrDefault("maxFetchPlayRecordCount0", 10_000.0, integer = true),
        )
        if (group == "t4") return State(Strategy.T4, params)
        val childData = envelopeData(child) ?: return State(Strategy.DEFAULT, params)
        val name = "RandomPlaylist_Adjustment_${group.uppercase()}_Android"
        val childAssignment = childData.get(name)?.objectOrNull()
        val variant = when (childAssignment?.get("expGroupName")?.stringOrNull()) {
            "t1" -> "T1"
            "t2" -> "T2"
            else -> "C"
        }
        val subgroupSize = if (variant == "C") 20.0 else {
            val childConfig = childAssignment?.get("clientConfig")?.objectOrNull() ?: JsonObject()
            val key = when (group) { "t1" -> "a4"; "t2" -> "a5"; else -> "a6" }
            childConfig.numberOrDefault(key, if (variant == "T1") 5.0 else 10.0, integer = true)
        }
        return State(Strategy.valueOf("${group.uppercase()}_$variant"), params.copy(subgroupSize = subgroupSize))
    }

    fun shuffle(
        ids: List<String>, playingId: String?, state: State, history: JsonArray = JsonArray(),
        nowMs: Long = System.currentTimeMillis(), notShuffleIds: List<String> = emptyList(),
        random: () -> Double = Random.Default::nextDouble,
    ): List<String> {
        val remaining = ids.toMutableList()
        notShuffleIds.forEach { remaining.remove(it) }
        remaining.sortWith(compareBy<String> { it.length }.thenBy { it })
        if (state.strategy == Strategy.DEFAULT || state.params == null) {
            swapToHead(pureShuffle(remaining, random), playingId)
        } else if (remaining.isNotEmpty()) {
            if (remaining.size < 10) state.strategy = Strategy.PURE
            val currentIndex = remaining.indexOf(playingId)
            val current = if (currentIndex >= 0) remaining.removeAt(currentIndex) else playingId
            val historyById = historyById(history, state.params, nowMs)
            when (state.strategy) {
                Strategy.T4 -> playCountOrder(remaining, historyById, random)
                Strategy.T1_C, Strategy.T1_T1, Strategy.T1_T2,
                Strategy.T2_C, Strategy.T2_T1, Strategy.T2_T2 -> {
                    weightedOrder(remaining, historyById, state.params, 1, random)
                    shuffleAdjacentGroups(remaining, if (state.strategy == Strategy.T1_C || state.strategy == Strategy.T2_C)
                        20.0 else state.params.subgroupSize, random)
                }
                Strategy.T3_C, Strategy.T3_T1, Strategy.T3_T2 -> {
                    weightedOrder(remaining, historyById, state.params, 2, random)
                    shuffleAdjacentGroups(remaining, if (state.strategy == Strategy.T3_C) 20.0 else state.params.subgroupSize, random)
                }
                else -> pureShuffle(remaining, random)
            }
            if (current != null) remaining.add(0, current)
        }
        return remaining + notShuffleIds
    }

    internal fun pureShuffle(ids: MutableList<String>, random: () -> Double): MutableList<String> {
        var size = ids.size
        while (size > 1) {
            val index = floor(random() * size).toInt()
            val tail = size - 1
            val value = ids[tail]
            ids[tail] = ids[index]
            ids[index] = value
            size--
        }
        return ids
    }

    internal fun historyById(history: JsonArray, params: Parameters, nowMs: Long): Map<String, HistoryRecord> {
        val count = params.maxFetchPlayRecordCount
        if (!count.isFinite()) return emptyMap()
        val records = history.mapNotNull { value ->
            val row = value.objectOrNull() ?: return@mapNotNull null
            val id = row.get("id")?.stringOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val timestamp = row.get("timestamp")?.numberOrNull() ?: return@mapNotNull null
            if (!timestamp.isFinite() || timestamp % 1 != 0.0 || kotlin.math.abs(timestamp) > 9_007_199_254_740_991.0 ||
                timestamp < nowMs - TWO_YEARS_MS) return@mapNotNull null
            val times = row.get("play_times")?.numberOrNull()?.takeIf {
                it.isFinite() && it % 1 == 0.0 && it in 0.0..2_147_483_647.0
            } ?: 0.0
            Triple(id, timestamp, times)
        }.sortedByDescending { it.second }
        val selected = if (count < 0) records else records.take(count.toInt().coerceAtLeast(0))
        val result = linkedMapOf<String, HistoryRecord>()
        selected.forEach { (id, timestamp, times) ->
            val first = result[id] ?: HistoryRecord(times, null)
            result[id] = if (timestamp != 0.0) first.copy(toLastPlayDays = (nowMs - timestamp) / 86_400_000) else first
        }
        return result
    }

    internal fun historyWeight(id: String, history: Map<String, HistoryRecord>, params: Parameters, approach: Int): Double {
        val record = history[id]
        val count = record?.playTimes ?: 0.0
        val days = record?.toLastPlayDays ?: 365.0
        val suppression = if (days <= params.d) params.e else 1.0
        return if (approach == 1) {
            (params.a * sqrt(count) / (1 + params.c * days) + params.b * log2(days + 1)) * suppression
        } else {
            (params.a / (1 + ln(count + 1)) + params.b * log2(days + 10)) * suppression
        }
    }

    internal fun playCountOrder(ids: MutableList<String>, history: Map<String, HistoryRecord>, random: () -> Double) {
        val groups = ids.groupBy { history[it]?.playTimes ?: 0.0 }.toSortedMap()
        ids.clear()
        groups.values.forEach { ids.addAll(pureShuffle(it.toMutableList(), random)) }
        shuffleAdjacentGroups(ids, 10.0, random)
    }

    private fun weightedOrder(ids: MutableList<String>, history: Map<String, HistoryRecord>, params: Parameters,
        approach: Int, random: () -> Double) {
        val groups = linkedMapOf<Double, MutableList<String>>()
        ids.forEach { id ->
            val rounded = floor(historyWeight(id, history, params, approach) * 10_000 + .5) / 10_000
            val weight = if (rounded == 0.0) 0.0 else rounded
            groups.getOrPut(weight) { mutableListOf() }.add(id)
        }
        val weights = groups.keys.sortedWith { a, b ->
            val difference = b - a
            when { difference.isNaN() || difference == 0.0 -> 0; difference > 0 -> 1; else -> -1 }
        }
        ids.clear()
        weights.forEach { ids.addAll(pureShuffle(groups.getValue(it), random)) }
    }

    private fun shuffleAdjacentGroups(ids: MutableList<String>, size: Double, random: () -> Double) {
        if (size <= 1 || size.isNaN()) return
        var start = 0
        val groupSize = if (size >= Int.MAX_VALUE) Int.MAX_VALUE else size.toInt()
        while (start < ids.size) {
            val end = (start.toLong() + groupSize).coerceAtMost(ids.size.toLong()).toInt()
            val subgroup = pureShuffle(ids.subList(start, end).toMutableList(), random)
            subgroup.forEachIndexed { index, id -> ids[start + index] = id }
            if (groupSize >= ids.size - start) break
            start += groupSize
        }
    }

    private fun swapToHead(ids: MutableList<String>, playingId: String?) {
        val index = ids.indexOf(playingId)
        if (index >= 0) {
            val value = ids[0]
            ids[0] = ids[index]
            ids[index] = value
        }
    }

    private fun envelopeData(value: JsonObject?): JsonObject? = value?.takeIf {
        val code = it.get("code")
        code?.isJsonPrimitive == true && code.asJsonPrimitive.isNumber && code.asDouble == 200.0
    }?.get("data")?.objectOrNull()
    private fun JsonElement.objectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonElement.stringOrNull(): String? = takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }?.asString
    private fun JsonElement.numberOrNull(): Double? = stringOrNull()?.toDoubleOrNull()

    private fun JsonObject.numberOrDefault(key: String, fallback: Double, integer: Boolean = false): Double {
        if (!has(key)) return fallback
        val value = get(key)
        if (value == null || !value.isJsonPrimitive || value.asJsonPrimitive.isBoolean) return Double.NaN
        val primitive = value.asJsonPrimitive
        if (primitive.isNumber) {
            val number = primitive.asDouble
            if (!integer || number == 0.0) return number
            if (!number.isFinite()) return Double.NaN
            if (kotlin.math.abs(number) in 1e-6..<1e21) return if (number < 0) -floor(-number) else floor(number)
            // JavaScript renders these magnitudes in exponent notation before parseInt.
            val leading = kotlin.math.abs(number).toString().first { it in '1'..'9' }.digitToInt()
            return if (number < 0) -leading.toDouble() else leading.toDouble()
        }
        val text = primitive.asString.trim { it.isWhitespace() || it == '\uFEFF' || it == '\u00A0' }
        if (!integer) return FLOAT_PREFIX.find(text)?.value?.toDoubleOrNull() ?: Double.NaN
        val sign = if (text.startsWith('-')) -1 else 1
        val unsigned = if (text.startsWith('+') || text.startsWith('-')) text.substring(1) else text
        val hexadecimal = unsigned.startsWith("0x", true)
        val digits = (if (hexadecimal) unsigned.substring(2) else unsigned).takeWhile {
            if (hexadecimal) it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' else it in '0'..'9'
        }
        if (digits.isEmpty()) return Double.NaN
        return digits.fold(0.0) { result, digit -> result * (if (hexadecimal) 16 else 10) + digit.digitToInt(if (hexadecimal) 16 else 10) } * sign
    }

    private val FLOAT_PREFIX = Regex("^[+-]?(?:Infinity|(?:(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?))")
}
