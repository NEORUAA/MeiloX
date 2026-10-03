package com.ljyh.mei.data.network

import java.math.BigInteger
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** URS's three bounded computation challenges, without its module/cache framework. */
internal object NeteaseUrsPuzzle {
    internal data class Answer(val id: String, val question: String, val values: Map<String, Any>)

    suspend fun solve(data: JSONObject): Answer {
        val questions = data.optJSONArray("compQues") ?: error("URS computation challenge is empty")
        check(questions.length() > 0) { "URS computation challenge is empty" }
        val puzzle = questions.getJSONObject(0)
        val args = puzzle.getJSONObject("args")
        val question = args.getString("puzzle")
        val minimum = puzzle.optLong("minTime", 0).coerceIn(0, 20_000)
        val maximum = puzzle.optLong("maxTime", 20_000).coerceIn(minimum, 20_000)
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        var count = 1L
        val values: Map<String, Any>
        when (puzzle.getString("hashFunc")) {
            "SEQ_HASHCASH", "RECUR_HASHCASH" -> {
                val recursive = puzzle.getString("hashFunc") == "RECUR_HASHCASH"
                val target = BigInteger(args.getString("target"), 16)
                val digest = MessageDigest.getInstance("SHA-256")
                var lowest: String? = null
                var nonce = ""
                var previous = ""
                var bestNonce = ""
                var bestPrevious = ""
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val hash = digest.digest((question + if (recursive) nonce else count.toString())
                        .toByteArray(Charsets.UTF_8)).toHex()
                    if (lowest == null || hash < lowest) {
                        lowest = hash; bestNonce = nonce; bestPrevious = previous
                    }
                    if (elapsed() > maximum || (elapsed() > minimum && BigInteger(lowest, 16) < target)) break
                    count++; previous = nonce; nonce = hash
                }
                values = buildMap {
                    put("pow", checkNotNull(lowest))
                    if (recursive) { put("n1", bestNonce); put("n2", bestPrevious) } else put("n", count)
                    put("runTimes", count); put("spendTime", elapsed())
                }
            }
            "VDF_FUNCTION" -> {
                val modulus = BigInteger(args.getString("mod"), 16)
                var x = BigInteger(args.getString("x"), 16)
                val rounds = args.getString("t").toLong()
                count = 0
                while (count < rounds || elapsed() < minimum) {
                    currentCoroutineContext().ensureActive()
                    x = x.modPow(BigInteger.TWO, modulus); count++
                    if (elapsed() > maximum) break
                }
                values = mapOf("runTimes" to count, "x" to x.toString(16),
                    "t" to count, "spendTime" to elapsed())
            }
            else -> error("Unsupported URS computation algorithm")
        }
        return Answer(puzzle.getString("sid"), question, values)
    }
}
