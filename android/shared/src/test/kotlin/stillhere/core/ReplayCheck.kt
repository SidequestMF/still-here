package stillhere.core

import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Replays the exact sensor streams recorded from tests/simulate-nights.mjs (TRACE_DIR=...)
 * through the Kotlin detection code and checks it makes the same decisions at the same moments.
 *
 *   TRACE_DIR=/tmp/traces node tests/simulate-nights.mjs
 *   then run this with the directory as its argument (see tests/run-kotlin-checks.sh).
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: error("usage: ReplayCheck <trace dir>"))
    var failures = 0
    val files = dir.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }
    for (file in files) {
        val lines = file.readLines()
        val name = lines[0].removePrefix("# ")
        val stop = lines[1] == "STOP 1"
        val expected = lines[2].removePrefix("EXPECT").trim().split(" ").filter { it.isNotEmpty() }

        val hr = HeartRateWatcher(0.0)
        val mw = MotionWatcher(0.0)
        val fired = mutableListOf<Pair<String, Double>>()
        loop@ for (line in lines.drop(3)) {
            if (line.isBlank()) continue
            val (kind, ts, vs) = line.split(" ")
            val t = ts.toDouble(); val v = vs.toDouble()
            when (kind) {
                "M" -> {
                    val ev = mw.add(v, t)
                    if (ev != null) {
                        fired += (if (ev == MotionWatcher.Event.STUMBLE) "stumble" else "stillness") to t / 60
                        if (stop) break@loop
                    }
                }
                "H" -> if (hr.add(v, t, mw.secondsSinceMovement(t), mw.intensity)) {
                    fired += "heartRate" to t / 60
                    hr.snooze(t)
                    if (stop) break@loop
                }
            }
        }
        val exp = expected.map { val (a, b) = it.split("@"); a to b.toDouble() }
        val same = exp.size == fired.size && exp.zip(fired).all { (e, f) -> e.first == f.first && abs(e.second - f.second) < 1e-3 }
        if (!same) {
            failures++
            println("MISMATCH ${file.name}  $name\n   javascript: $exp\n   kotlin:     $fired")
        }
    }
    println(if (failures == 0) "Kotlin matched JavaScript on all ${files.size} recorded nights." else "$failures of ${files.size} nights differ.")
    if (failures > 0) exitProcess(1)
}
