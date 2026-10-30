package com.automaforge.android.core

import java.time.Instant
import org.json.JSONObject

/** JSON is the extensible public game contract; it is independent of storage and UI. */
object GameJson {
    @JvmStatic
    fun obj(vararg values: Any?): JSONObject =
        JSONObject().apply {
            var index = 0
            while (index + 1 < values.size) {
                put(values[index] as String, values[index + 1])
                index += 2
            }
        }

    @JvmStatic fun now(): String = Instant.now().toString()

    @JvmStatic fun clip(value: String?, max: Int): String = value.orEmpty().take(max)
}

class ApiException(@JvmField val status: Int, reason: String) : Exception(reason)
