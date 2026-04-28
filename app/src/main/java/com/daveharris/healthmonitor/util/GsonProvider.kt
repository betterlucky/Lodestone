package com.daveharris.healthmonitor.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

object GsonProvider {
    private fun <T> temporalAdapter(
        parse: (String) -> T,
        format: (T) -> String = { it.toString() }
    ) = Pair(
        JsonSerializer<T> { src, _, _ -> JsonPrimitive(src?.let(format)) },
        JsonDeserializer { json, _, _ -> parse(json.asString) }
    )

    val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .apply {
            val localDate = temporalAdapter(LocalDate::parse)
            registerTypeAdapter(LocalDate::class.java, localDate.first)
            registerTypeAdapter(LocalDate::class.java, localDate.second)

            val localTime = temporalAdapter(LocalTime::parse)
            registerTypeAdapter(LocalTime::class.java, localTime.first)
            registerTypeAdapter(LocalTime::class.java, localTime.second)

            val localDateTime = temporalAdapter(LocalDateTime::parse)
            registerTypeAdapter(LocalDateTime::class.java, localDateTime.first)
            registerTypeAdapter(LocalDateTime::class.java, localDateTime.second)

            val zonedDateTime = temporalAdapter(ZonedDateTime::parse)
            registerTypeAdapter(ZonedDateTime::class.java, zonedDateTime.first)
            registerTypeAdapter(ZonedDateTime::class.java, zonedDateTime.second)
        }
        .create()
}
