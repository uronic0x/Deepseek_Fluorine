package com.uronic0x.fluorine.engine

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 1:1 implementation of the 9 deterministic calculation tools in `src/tools.rs`:
 * `calc`, `stats`, `unit_convert`, `date_calc`, `text_metrics`, `json_query`,
 * `csv_summary`, `text_diff`, `encode_hash`.
 */
object DeterministicTools {

    fun toolDefinitions(): JSONArray {
        return JSONArray(
            """
            [
              {
                "type": "function",
                "function": {
                  "name": "calc",
                  "description": "Evaluate a mathematical expression accurately using local deterministic arithmetic. Supports +, -, *, /, %, ^, parentheses, constants (pi, e), and functions (sqrt, abs, round, floor, ceil, sin, cos, tan, asin, acos, atan, ln, log2, log10, exp, pow, min, max, clamp).",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "expression": {
                        "type": "string",
                        "description": "Mathematical expression, e.g. '(1250 * 0.15) + sqrt(144)'"
                      }
                    },
                    "required": ["expression"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "stats",
                  "description": "Compute descriptive statistics (count, sum, mean, median, min, max, range, p90, p95, pop_std_dev, sample_std_dev, sample_variance) for a list of numbers.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "numbers": {
                        "type": "array",
                        "items": { "type": "number" },
                        "description": "List of numeric values"
                      }
                    },
                    "required": ["numbers"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "unit_convert",
                  "description": "Convert values between units of length (mm, cm, m, km, in, ft, mi), area (m2, km2, ha, sqft), mass (mg, g, kg, oz, lb), temperature (C, F, K), data (B, KB, MB, GB, TB, KiB, MiB, GiB), time (ms, s, min, h, d, wk, yr), energy (J, kJ, Wh, kWh, kcal), and pressure (Pa, kPa, bar, psi, atm).",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "value": { "type": "number" },
                      "from": { "type": "string" },
                      "to": { "type": "string" }
                    },
                    "required": ["value", "from", "to"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "date_calc",
                  "description": "Perform Gregorian calendar date calculations: 'diff' (days between date and date2), 'workdays' (Mon-Fri business days in [date, date2)), 'add' (add days to date), or 'weekday' (day of week for date). Dates must be YYYY-MM-DD.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "op": {
                        "type": "string",
                        "enum": ["diff", "workdays", "add", "weekday"]
                      },
                      "date": {
                        "type": "string",
                        "description": "Primary date in YYYY-MM-DD format"
                      },
                      "date2": {
                        "type": "string",
                        "description": "Second date in YYYY-MM-DD format (required for 'diff' and 'workdays')"
                      },
                      "days": {
                        "type": "integer",
                        "description": "Signed integer days to add (required for 'add')"
                      }
                    },
                    "required": ["op", "date"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "text_metrics",
                  "description": "Count Unicode characters, CJK characters, UTF-8 bytes, whitespace-delimited words, lines, and rough token estimate of a text.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "text": { "type": "string" }
                    },
                    "required": ["text"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "json_query",
                  "description": "Validate, format, inspect keys, or query a JSON string using an RFC 6901 JSON Pointer (e.g. '/items/0/price').",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "json_str": { "type": "string", "description": "Raw JSON text" },
                      "op": {
                        "type": "string",
                        "enum": ["query", "keys", "validate", "format"],
                        "description": "Operation to perform (default: 'query' if pointer given, else 'validate')"
                      },
                      "pointer": {
                        "type": "string",
                        "description": "RFC 6901 JSON Pointer path, e.g. '/data/0/id'"
                      }
                    },
                    "required": ["json_str"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "csv_summary",
                  "description": "Parse CSV or TSV text and compute row count, column headers, non-empty counts, and numeric column statistics (sum, min, max, mean).",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "csv_text": { "type": "string", "description": "CSV or TSV content including header row" },
                      "delimiter": {
                        "type": "string",
                        "description": "Single-character delimiter, e.g. ',' or '\\t' (default ',')"
                      }
                    },
                    "required": ["csv_text"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "text_diff",
                  "description": "Compute a deterministic line-by-line diff between old_text and new_text, returning added/removed/unchanged counts and diff lines.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "old_text": { "type": "string" },
                      "new_text": { "type": "string" }
                    },
                    "required": ["old_text", "new_text"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "encode_hash",
                  "description": "Deterministic encoding, decoding, and cryptographic/checksum hashing: base64_encode, base64_decode, hex_encode, hex_decode, url_encode, url_decode, crc32, fnv1a_64, sha256.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "op": {
                        "type": "string",
                        "enum": [
                          "base64_encode",
                          "base64_decode",
                          "hex_encode",
                          "hex_decode",
                          "url_encode",
                          "url_decode",
                          "crc32",
                          "fnv1a_64",
                          "sha256"
                        ]
                      },
                      "input": { "type": "string" }
                    },
                    "required": ["op", "input"]
                  }
                }
              }
            ]
            """.trimIndent()
        )
    }

    fun executeDeterministicTool(name: String, args: JSONObject): JSONObject? {
        return when (name) {
            "calc" -> runCalc(args)
            "stats" -> runStats(args)
            "unit_convert" -> runUnitConvert(args)
            "date_calc" -> runDateCalc(args)
            "text_metrics" -> runTextMetrics(args)
            "json_query" -> runJsonQuery(args)
            "csv_summary" -> runCsvSummary(args)
            "text_diff" -> runTextDiff(args)
            "encode_hash" -> runEncodeHash(args)
            else -> null
        }
    }

    // --- 1. Shunting-Yard Math Evaluator (`calc`) ---

    private sealed class Tok {
        data class Num(val v: Double) : Tok()
        data class Ident(val name: String) : Tok()
        data class Op(val c: Char) : Tok()
        object UnaryMinus : Tok()
        object LParen : Tok()
        object RParen : Tok()
        object Comma : Tok()
    }

    private sealed class OpItem {
        data class Op(val c: Char) : OpItem()
        object UnaryMinus : OpItem()
        data class Func(val name: String) : OpItem()
        object LParen : OpItem()
    }

    private fun prec(op: OpItem): Int = when (op) {
        is OpItem.Op -> when (op.c) {
            '+', '-' -> 1
            '*', '/', '%' -> 2
            '^' -> 4
            else -> 0
        }
        OpItem.UnaryMinus -> 3
        else -> 0
    }

    private fun isRightAssoc(op: OpItem): Boolean =
        op is OpItem.UnaryMinus || (op is OpItem.Op && op.c == '^')

    private fun tokenize(expr: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                if (i < expr.length && (expr[i] == 'e' || expr[i] == 'E')) {
                    i++
                    if (i < expr.length && (expr[i] == '+' || expr[i] == '-')) i++
                    while (i < expr.length && expr[i].isDigit()) i++
                }
                val s = expr.substring(start, i)
                val v = s.toDoubleOrNull() ?: throw IllegalArgumentException("invalid number: $s")
                out.add(Tok.Num(v))
                continue
            }
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < expr.length && (expr[i].isLetterOrDigit() || expr[i] == '_')) i++
                val ident = expr.substring(start, i).lowercase(Locale.ROOT)
                out.add(Tok.Ident(ident))
                continue
            }
            when (c) {
                '(' -> out.add(Tok.LParen)
                ')' -> out.add(Tok.RParen)
                ',' -> out.add(Tok.Comma)
                '+', '-', '*', '/', '%', '^' -> {
                    val last = out.lastOrNull()
                    val expectUnary = last == null || last is Tok.Op || last is Tok.UnaryMinus ||
                        last is Tok.LParen || last is Tok.Comma
                    if (expectUnary && c == '-') {
                        out.add(Tok.UnaryMinus)
                    } else if (expectUnary && c == '+') {
                        // no-op
                    } else {
                        out.add(Tok.Op(c))
                    }
                }
                else -> throw IllegalArgumentException("unexpected character: '$c'")
            }
            i++
        }
        return out
    }

    private fun pushOp(out: MutableList<Tok>, item: OpItem) {
        when (item) {
            is OpItem.Op -> out.add(Tok.Op(item.c))
            OpItem.UnaryMinus -> out.add(Tok.UnaryMinus)
            is OpItem.Func -> out.add(Tok.Ident(item.name))
            OpItem.LParen -> throw IllegalArgumentException("mismatched parentheses")
        }
    }

    fun evalExpr(expr: String): Double {
        if (expr.length > 2048) throw IllegalArgumentException("expression too long")
        val tokens = tokenize(expr)
        val output = mutableListOf<Tok>()
        val ops = mutableListOf<OpItem>()

        for ((idx, tok) in tokens.withIndex()) {
            when (tok) {
                is Tok.Num -> output.add(tok)
                is Tok.Ident -> {
                    val isCall = tokens.getOrNull(idx + 1) is Tok.LParen
                    if (isCall) {
                        ops.add(OpItem.Func(tok.name))
                    } else {
                        when (tok.name) {
                            "pi" -> output.add(Tok.Num(Math.PI))
                            "e" -> output.add(Tok.Num(Math.E))
                            else -> throw IllegalArgumentException("unknown constant: ${tok.name}")
                        }
                    }
                }
                Tok.Comma -> {
                    while (ops.isNotEmpty() && ops.last() !is OpItem.LParen) {
                        pushOp(output, ops.removeAt(ops.lastIndex))
                    }
                }
                Tok.LParen -> ops.add(OpItem.LParen)
                Tok.RParen -> {
                    var found = false
                    while (ops.isNotEmpty()) {
                        val item = ops.removeAt(ops.lastIndex)
                        if (item is OpItem.LParen) {
                            found = true
                            break
                        }
                        pushOp(output, item)
                    }
                    if (!found) throw IllegalArgumentException("mismatched parentheses")
                    if (ops.lastOrNull() is OpItem.Func) {
                        pushOp(output, ops.removeAt(ops.lastIndex))
                    }
                }
                Tok.UnaryMinus -> ops.add(OpItem.UnaryMinus)
                is Tok.Op -> {
                    val cur = OpItem.Op(tok.c)
                    while (ops.isNotEmpty()) {
                        val top = ops.last()
                        if (top is OpItem.LParen) break
                        val pTop = prec(top)
                        val pCur = prec(cur)
                        if (pTop > pCur || (pTop == pCur && !isRightAssoc(cur))) {
                            pushOp(output, ops.removeAt(ops.lastIndex))
                        } else {
                            break
                        }
                    }
                    ops.add(cur)
                }
            }
        }
        while (ops.isNotEmpty()) {
            val item = ops.removeAt(ops.lastIndex)
            if (item is OpItem.LParen) throw IllegalArgumentException("mismatched parentheses")
            pushOp(output, item)
        }

        val stack = mutableListOf<Double>()
        fun popArg(msg: String = "missing arg"): Double {
            if (stack.isEmpty()) throw IllegalArgumentException(msg)
            return stack.removeAt(stack.lastIndex)
        }

        for (item in output) {
            when (item) {
                is Tok.Num -> stack.add(item.v)
                Tok.UnaryMinus -> stack.add(-popArg("missing operand"))
                is Tok.Op -> {
                    val b = popArg("missing right operand")
                    val a = popArg("missing left operand")
                    val v = when (item.c) {
                        '+' -> a + b
                        '-' -> a - b
                        '*' -> a * b
                        '/' -> {
                            if (b == 0.0) throw IllegalArgumentException("division by zero")
                            a / b
                        }
                        '%' -> {
                            if (b == 0.0) throw IllegalArgumentException("modulo by zero")
                            a % b
                        }
                        '^' -> a.pow(b)
                        else -> throw IllegalArgumentException("unknown operator")
                    }
                    stack.add(v)
                }
                is Tok.Ident -> {
                    val v = when (item.name) {
                        "sqrt" -> sqrt(popArg())
                        "abs" -> abs(popArg())
                        "round" -> popArg().roundToLong().toDouble()
                        "floor" -> floor(popArg())
                        "ceil" -> ceil(popArg())
                        "sin" -> sin(popArg())
                        "cos" -> cos(popArg())
                        "tan" -> tan(popArg())
                        "asin" -> asin(popArg())
                        "acos" -> acos(popArg())
                        "atan" -> atan(popArg())
                        "ln" -> ln(popArg())
                        "log2" -> log2(popArg())
                        "log10" -> log10(popArg())
                        "exp" -> exp(popArg())
                        "pow" -> {
                            val b = popArg("missing arg 2")
                            val a = popArg("missing arg 1")
                            a.pow(b)
                        }
                        "min" -> {
                            val b = popArg("missing arg 2")
                            val a = popArg("missing arg 1")
                            min(a, b)
                        }
                        "max" -> {
                            val b = popArg("missing arg 2")
                            val a = popArg("missing arg 1")
                            max(a, b)
                        }
                        "clamp" -> {
                            val hi = popArg("missing arg 3 (max)")
                            val lo = popArg("missing arg 2 (min)")
                            val x = popArg("missing arg 1 (value)")
                            if (lo > hi) throw IllegalArgumentException("clamp min > max")
                            x.coerceIn(lo, hi)
                        }
                        else -> throw IllegalArgumentException("unknown function: ${item.name}")
                    }
                    stack.add(v)
                }
                else -> throw IllegalArgumentException("unexpected token in RPN")
            }
        }
        if (stack.size != 1) throw IllegalArgumentException("invalid expression")
        val res = stack[0]
        if (!res.isFinite()) throw IllegalArgumentException("result is not finite")
        return res
    }

    private fun runCalc(args: JSONObject): JSONObject {
        val expr = args.optString("expression").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'expression' string")
        val v = evalExpr(expr)
        val formatted = if (abs(v - v.roundToLong()) < 1e-12 && abs(v) < 1e15) {
            v.roundToLong().toString()
        } else {
            v.toString()
        }
        return JSONObject()
            .put("expression", expr)
            .put("value", v)
            .put("formatted", formatted)
    }

    // --- 2. Descriptive Statistics (`stats`) ---

    private fun percentileSorted(sorted: DoubleArray, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val idx = p * (sorted.size - 1)
        val lo = floor(idx).toInt()
        val hi = ceil(idx).toInt()
        val frac = idx - lo
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac
    }

    private fun runStats(args: JSONObject): JSONObject {
        val arr = args.optJSONArray("numbers")
            ?: throw IllegalArgumentException("missing 'numbers' array")
        if (arr.length() == 0) throw IllegalArgumentException("numbers array must not be empty")
        if (arr.length() > 100_000) throw IllegalArgumentException("numbers array too large (max 100000)")
        val nums = DoubleArray(arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.optDouble(i, Double.NaN)
            if (!n.isFinite()) throw IllegalArgumentException("all elements must be finite numbers")
            nums[i] = n
        }
        nums.sort()
        val count = nums.size
        val sum = nums.sum()
        val mean = sum / count
        val median = percentileSorted(nums, 0.5)
        val p90 = percentileSorted(nums, 0.9)
        val p95 = percentileSorted(nums, 0.95)
        var sqDiffSum = 0.0
        for (x in nums) {
            val d = x - mean
            sqDiffSum += d * d
        }
        val popVar = sqDiffSum / count
        val sampleVar = if (count > 1) sqDiffSum / (count - 1) else 0.0
        return JSONObject()
            .put("count", count)
            .put("sum", sum)
            .put("mean", mean)
            .put("median", median)
            .put("min", nums[0])
            .put("max", nums[count - 1])
            .put("range", nums[count - 1] - nums[0])
            .put("p90", p90)
            .put("p95", p95)
            .put("pop_std_dev", sqrt(popVar))
            .put("sample_std_dev", sqrt(sampleVar))
            .put("sample_variance", sampleVar)
    }

    // --- 3. Unit Converter (`unit_convert`) ---

    private fun toCelsius(v: Double, u: String): Double? = when (u.uppercase(Locale.ROOT)) {
        "C" -> v
        "F" -> (v - 32.0) * 5.0 / 9.0
        "K" -> v - 273.15
        else -> null
    }

    private fun fromCelsius(c: Double, u: String): Double? = when (u.uppercase(Locale.ROOT)) {
        "C" -> c
        "F" -> c * 9.0 / 5.0 + 32.0
        "K" -> c + 273.15
        else -> null
    }

    private fun unitFactor(u: String): Pair<String, Double> = when (u) {
        "mm" -> "len" to 0.001
        "cm" -> "len" to 0.01
        "m" -> "len" to 1.0
        "km" -> "len" to 1000.0
        "in" -> "len" to 0.0254
        "ft" -> "len" to 0.3048
        "mi" -> "len" to 1609.344
        "m2" -> "area" to 1.0
        "km2" -> "area" to 1e6
        "ha" -> "area" to 1e4
        "sqft" -> "area" to 0.09290304
        "mg" -> "mass" to 1e-6
        "g" -> "mass" to 0.001
        "kg" -> "mass" to 1.0
        "oz" -> "mass" to 0.028349523125
        "lb" -> "mass" to 0.45359237
        "B" -> "data" to 1.0
        "KB" -> "data" to 1000.0
        "MB" -> "data" to 1e6
        "GB" -> "data" to 1e9
        "TB" -> "data" to 1e12
        "KiB" -> "data" to 1024.0
        "MiB" -> "data" to 1024.0 * 1024.0
        "GiB" -> "data" to 1024.0 * 1024.0 * 1024.0
        "ms" -> "time" to 0.001
        "s" -> "time" to 1.0
        "min" -> "time" to 60.0
        "h" -> "time" to 3600.0
        "d" -> "time" to 86400.0
        "wk" -> "time" to 604800.0
        "yr" -> "time" to 31536000.0
        "J" -> "energy" to 1.0
        "kJ" -> "energy" to 1000.0
        "Wh" -> "energy" to 3600.0
        "kWh" -> "energy" to 3_600_000.0
        "kcal" -> "energy" to 4184.0
        "Pa" -> "pressure" to 1.0
        "kPa" -> "pressure" to 1000.0
        "bar" -> "pressure" to 100_000.0
        "psi" -> "pressure" to 6894.757293168361
        "atm" -> "pressure" to 101_325.0
        else -> throw IllegalArgumentException("unsupported unit: $u")
    }

    private fun runUnitConvert(args: JSONObject): JSONObject {
        if (!args.has("value")) throw IllegalArgumentException("missing 'value' number")
        val value = args.optDouble("value", Double.NaN)
        if (!value.isFinite()) throw IllegalArgumentException("non-finite value")
        val from = args.optString("from").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'from'")
        val to = args.optString("to").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'to'")

        val cVal = toCelsius(value, from)
        val tempOut = if (cVal != null) fromCelsius(cVal, to) else null
        if (tempOut != null) {
            return JSONObject()
                .put("value", value)
                .put("from", from)
                .put("to", to)
                .put("result", tempOut)
        }
        val (catF, mulF) = unitFactor(from)
        val (catT, mulT) = unitFactor(to)
        if (catF != catT) throw IllegalArgumentException("incompatible unit categories: $from vs $to")
        val result = value * mulF / mulT
        return JSONObject()
            .put("value", value)
            .put("from", from)
            .put("to", to)
            .put("result", result)
    }

    // --- 4. Howard Hinnant Civil Calendar (`date_calc`) ---

    private fun daysFromCivil(yIn: Long, m: Int, d: Int): Long {
        val y = yIn - if (m <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mAdj = (if (m > 2) m - 3 else m + 9).toLong()
        val doy = (153 * mAdj + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun civilFromDays(zIn: Long): Triple<Long, Int, Int> {
        val z = zIn + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        if (m <= 2) y += 1
        return Triple(y, m, d)
    }

    private fun parseYmd(s: String): Triple<Long, Int, Int> {
        val parts = s.split('-')
        if (parts.size != 3) throw IllegalArgumentException("invalid date '$s', expected YYYY-MM-DD")
        val y = parts[0].toLongOrNull() ?: throw IllegalArgumentException("invalid year")
        val m = parts[1].toIntOrNull() ?: throw IllegalArgumentException("invalid month")
        val d = parts[2].toIntOrNull() ?: throw IllegalArgumentException("invalid day")
        if (m !in 1..12 || d !in 1..31) throw IllegalArgumentException("date out of range: $s")
        val days = daysFromCivil(y, m, d)
        if (civilFromDays(days) != Triple(y, m, d)) {
            throw IllegalArgumentException("non-existent calendar date: $s")
        }
        return Triple(y, m, d)
    }

    private fun weekdayIdx(days: Long): Int = Math.floorMod(days + 4L, 7L).toInt()

    private fun weekdayName(days: Long): String = arrayOf(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    )[weekdayIdx(days)]

    private fun countWorkdaysHalfOpen(startDays: Long, endDays: Long): Long {
        if (startDays == endDays) return 0
        val sign = if (endDays >= startDays) 1L else -1L
        val lo = min(startDays, endDays)
        val hi = max(startDays, endDays)
        val span = hi - lo
        val fullWeeks = span / 7
        val rem = (span % 7).toInt()
        var work = fullWeeks * 5
        for (i in 0 until rem) {
            val w = weekdayIdx(lo + i)
            if (w in 1..5) work += 1
        }
        return work * sign
    }

    private fun runDateCalc(args: JSONObject): JSONObject {
        val op = args.optString("op").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'op'")
        val date = args.optString("date").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'date'")
        val (y, m, d) = parseYmd(date)
        val days1 = daysFromCivil(y, m, d)
        return when (op) {
            "diff" -> {
                val date2 = args.optString("date2").takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("missing 'date2'")
                val (y2, m2, d2) = parseYmd(date2)
                val days2 = daysFromCivil(y2, m2, d2)
                JSONObject()
                    .put("date", date)
                    .put("date2", date2)
                    .put("days_between", days2 - days1)
            }
            "workdays" -> {
                val date2 = args.optString("date2").takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("missing 'date2'")
                val (y2, m2, d2) = parseYmd(date2)
                val days2 = daysFromCivil(y2, m2, d2)
                val workdays = countWorkdaysHalfOpen(days1, days2)
                JSONObject()
                    .put("date", date)
                    .put("date2", date2)
                    .put("days_between", days2 - days1)
                    .put("workdays", workdays)
            }
            "add" -> {
                if (!args.has("days")) throw IllegalArgumentException("missing 'days' integer")
                val delta = args.optLong("days", 0)
                val (ry, rm, rd) = civilFromDays(days1 + delta)
                val res = String.format(Locale.US, "%04d-%02d-%02d", ry, rm, rd)
                JSONObject()
                    .put("date", date)
                    .put("days", delta)
                    .put("result", res)
                    .put("weekday", weekdayName(days1 + delta))
            }
            "weekday" -> JSONObject()
                .put("date", date)
                .put("weekday", weekdayName(days1))
            else -> throw IllegalArgumentException("unsupported date_calc op: $op")
        }
    }

    // --- 5. Text Metrics (`text_metrics`) ---

    private fun isCjkCodePoint(cp: Int): Boolean {
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
            cp in 0xF900..0xFAFF || cp in 0x20000..0x2A6DF
    }

    private fun runTextMetrics(args: JSONObject): JSONObject {
        if (!args.has("text")) throw IllegalArgumentException("missing 'text'")
        val text = args.optString("text")
        val chars = text.codePointCount(0, text.length)
        var cjkChars = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isCjkCodePoint(cp)) cjkChars++
            i += Character.charCount(cp)
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8).size
        val words = text.trim().let { if (it.isEmpty()) 0 else it.split(Regex("\\s+")).size }
        val asciiWords = text.split(Regex("[^A-Za-z0-9]+")).count { it.isNotEmpty() }
        val lines = if (text.isEmpty()) 0 else text.lines().size
        val estTokens = ceil(chars * 0.6).toInt()
        return JSONObject()
            .put("chars", chars)
            .put("cjk_chars", cjkChars)
            .put("bytes", bytes)
            .put("words", words)
            .put("ascii_words", asciiWords)
            .put("lines", lines)
            .put("estimated_tokens", estTokens)
    }

    // --- 6. RFC 6901 JSON Pointer (`json_query`) ---

    private fun parseAnyJson(raw: String): Any? {
        val wrapped = JSONObject("{\"v\":$raw}")
        if (wrapped.isNull("v")) return JSONObject.NULL
        return wrapped.get("v")
    }

    private fun jsonValueType(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is Boolean -> "boolean"
        is Number -> "number"
        is String -> "string"
        is JSONArray -> "array"
        is JSONObject -> "object"
        else -> "string"
    }

    private fun resolveJsonPointer(root: Any?, pointer: String): Any? {
        if (pointer.isEmpty()) return root
        if (!pointer.startsWith("/")) {
            throw IllegalArgumentException("pointer not found: $pointer")
        }
        val tokens = pointer.substring(1).split("/").map {
            it.replace("~1", "/").replace("~0", "~")
        }
        var cur: Any? = root
        for (tok in tokens) {
            cur = when (cur) {
                is JSONObject -> {
                    if (!cur.has(tok)) throw IllegalArgumentException("pointer not found: $pointer")
                    if (cur.isNull(tok)) JSONObject.NULL else cur.get(tok)
                }
                is JSONArray -> {
                    val idx = tok.toIntOrNull()
                        ?: throw IllegalArgumentException("pointer not found: $pointer")
                    if (idx < 0 || idx >= cur.length()) {
                        throw IllegalArgumentException("pointer not found: $pointer")
                    }
                    if (cur.isNull(idx)) JSONObject.NULL else cur.get(idx)
                }
                else -> throw IllegalArgumentException("pointer not found: $pointer")
            }
        }
        return cur
    }

    private fun runJsonQuery(args: JSONObject): JSONObject {
        if (!args.has("json_str")) throw IllegalArgumentException("missing 'json_str'")
        val raw = args.optString("json_str")
        if (raw.toByteArray(StandardCharsets.UTF_8).size > 512 * 1024) {
            throw IllegalArgumentException("json_str exceeds 512 KiB limit")
        }
        val parsed = try {
            parseAnyJson(raw.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid JSON: ${e.message}")
        }
        val hasPointer = args.has("pointer") && !args.isNull("pointer")
        val pointer = if (hasPointer) args.optString("pointer") else null
        val op = if (args.has("op") && !args.isNull("op")) {
            args.optString("op")
        } else {
            if (pointer != null) "query" else "validate"
        }

        return when (op) {
            "validate" -> {
                val len: Any = when (parsed) {
                    is JSONArray -> parsed.length()
                    is JSONObject -> parsed.length()
                    else -> JSONObject.NULL
                }
                JSONObject()
                    .put("valid", true)
                    .put("type", jsonValueType(parsed))
                    .put("length", len)
            }
            "format" -> {
                val formatted = when (parsed) {
                    is JSONObject -> parsed.toString(2)
                    is JSONArray -> parsed.toString(2)
                    JSONObject.NULL -> "null"
                    is String -> JSONObject.quote(parsed)
                    else -> parsed.toString()
                }
                JSONObject()
                    .put("valid", true)
                    .put("formatted", formatted)
            }
            "keys" -> {
                val target = if (!pointer.isNullOrEmpty()) resolveJsonPointer(parsed, pointer) else parsed
                val obj = target as? JSONObject
                    ?: throw IllegalArgumentException("target is not a JSON object")
                val keysArr = JSONArray()
                val iter = obj.keys()
                while (iter.hasNext()) {
                    keysArr.put(iter.next())
                }
                JSONObject()
                    .put("keys", keysArr)
                    .put("count", keysArr.length())
            }
            "query" -> {
                val p = pointer ?: ""
                val target = resolveJsonPointer(parsed, p)
                JSONObject()
                    .put("pointer", p)
                    .put("type", jsonValueType(target))
                    .put("value", target ?: JSONObject.NULL)
            }
            else -> throw IllegalArgumentException("unsupported json_query op: $op")
        }
    }

    // --- 7. CSV/TSV Summary (`csv_summary`) ---

    private fun parseCsvRows(text: String, delim: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else if (c == '"' && field.isEmpty()) {
                inQuotes = true
            } else if (c == delim) {
                row.add(field.toString())
                field.setLength(0)
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                    i++
                }
                row.add(field.toString())
                field.setLength(0)
                if (!(row.size == 1 && row[0].isEmpty())) {
                    rows.add(row)
                }
                row = mutableListOf()
            } else {
                field.append(c)
            }
            i++
        }
        if (inQuotes) throw IllegalArgumentException("unterminated quoted field in CSV")
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private fun runCsvSummary(args: JSONObject): JSONObject {
        if (!args.has("csv_text")) throw IllegalArgumentException("missing 'csv_text'")
        val text = args.optString("csv_text")
        if (text.toByteArray(StandardCharsets.UTF_8).size > 512 * 1024) {
            throw IllegalArgumentException("csv_text exceeds 512 KiB limit")
        }
        val delimStr = if (args.has("delimiter") && !args.isNull("delimiter")) {
            args.optString("delimiter")
        } else {
            ","
        }
        val delim = when {
            delimStr == "\\t" -> '\t'
            delimStr.length == 1 -> delimStr[0]
            else -> throw IllegalArgumentException("delimiter must be a single character")
        }
        val rows = parseCsvRows(text, delim)
        if (rows.isEmpty()) throw IllegalArgumentException("CSV is empty")
        val headers = rows[0]
        val dataRows = rows.drop(1)
        val columns = JSONArray()
        for ((colIdx, name) in headers.withIndex()) {
            var nonEmpty = 0
            val nums = mutableListOf<Double>()
            for (r in dataRows) {
                val cell = r.getOrNull(colIdx)?.trim() ?: ""
                if (cell.isNotEmpty()) {
                    nonEmpty++
                    val v = cell.toDoubleOrNull()
                    if (v != null && v.isFinite()) nums.add(v)
                }
            }
            val col = JSONObject()
                .put("name", name)
                .put("non_empty", nonEmpty)
                .put("numeric_count", nums.size)
            if (nums.isNotEmpty()) {
                val sum = nums.sum()
                col.put("sum", sum)
                col.put("min", nums.minOrNull()!!)
                col.put("max", nums.maxOrNull()!!)
                col.put("mean", sum / nums.size)
            }
            columns.put(col)
        }
        return JSONObject()
            .put("row_count", dataRows.size)
            .put("column_count", headers.size)
            .put("headers", JSONArray(headers))
            .put("columns", columns)
    }

    // --- 8. Deterministic Line Diff (`text_diff`) ---

    private fun runTextDiff(args: JSONObject): JSONObject {
        if (!args.has("old_text")) throw IllegalArgumentException("missing 'old_text'")
        if (!args.has("new_text")) throw IllegalArgumentException("missing 'new_text'")
        val oldText = args.optString("old_text")
        val newText = args.optString("new_text")
        val a = if (oldText.isEmpty()) emptyList() else oldText.lines()
        val b = if (newText.isEmpty()) emptyList() else newText.lines()
        if (a.size > 400 || b.size > 400) {
            throw IllegalArgumentException("text_diff supports up to 400 lines per input")
        }
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    max(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val diffLines = mutableListOf<String>()
        var i = 0
        var j = 0
        var added = 0
        var removed = 0
        var unchanged = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                diffLines.add("  ${a[i]}")
                unchanged++
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                diffLines.add("- ${a[i]}")
                removed++
                i++
            } else {
                diffLines.add("+ ${b[j]}")
                added++
                j++
            }
        }
        while (i < n) {
            diffLines.add("- ${a[i]}")
            removed++
            i++
        }
        while (j < m) {
            diffLines.add("+ ${b[j]}")
            added++
            j++
        }
        return JSONObject()
            .put("added", added)
            .put("removed", removed)
            .put("unchanged", unchanged)
            .put("diff", diffLines.joinToString("\n"))
    }

    // --- 9. Deterministic Encoding & Hashing (`encode_hash`) ---

    private fun hexEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun hexDecode(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }
        if (clean.length % 2 != 0) throw IllegalArgumentException("invalid hex length")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) throw IllegalArgumentException("invalid hex character")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun urlEncodeRfc3986(s: String): String {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            val c = u.toChar()
            if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                c == '-' || c == '_' || c == '.' || c == '~'
            ) {
                sb.append(c)
            } else {
                sb.append(String.format("%%%02X", u))
            }
        }
        return sb.toString()
    }

    private fun fnv1a64(bytes: ByteArray): ULong {
        var h: ULong = 0xCBF29CE484222325uL
        val prime: ULong = 0x0100000001B3uL
        for (b in bytes) {
            h = h xor (b.toInt() and 0xFF).toULong()
            h *= prime
        }
        return h
    }

    private fun runEncodeHash(args: JSONObject): JSONObject {
        val op = args.optString("op").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'op'")
        if (!args.has("input")) throw IllegalArgumentException("missing 'input'")
        val input = args.optString("input")
        val inputBytes = input.toByteArray(StandardCharsets.UTF_8)
        if (inputBytes.size > 512 * 1024) {
            throw IllegalArgumentException("input exceeds 512 KiB limit")
        }
        val result: String = when (op) {
            "base64_encode" -> Base64.encodeToString(inputBytes, Base64.NO_WRAP)
            "base64_decode" -> {
                val decoded = try {
                    Base64.decode(input, Base64.DEFAULT)
                } catch (_: Exception) {
                    throw IllegalArgumentException("invalid base64 input")
                }
                String(decoded, StandardCharsets.UTF_8)
            }
            "hex_encode" -> hexEncode(inputBytes)
            "hex_decode" -> String(hexDecode(input), StandardCharsets.UTF_8)
            "url_encode" -> urlEncodeRfc3986(input)
            "url_decode" -> URLDecoder.decode(input, StandardCharsets.UTF_8.name())
            "crc32" -> {
                val crc = CRC32()
                crc.update(inputBytes)
                String.format("%08x", crc.value)
            }
            "fnv1a_64" -> {
                val h = fnv1a64(inputBytes)
                h.toString(16).padStart(16, '0')
            }
            "sha256" -> {
                val md = MessageDigest.getInstance("SHA-256")
                hexEncode(md.digest(inputBytes))
            }
            else -> throw IllegalArgumentException("unsupported encode_hash op: $op")
        }
        return JSONObject()
            .put("op", op)
            .put("result", result)
    }
}
