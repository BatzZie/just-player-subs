package autosubs.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient JSON access in the style of org.json's optXxx(), on kotlinx.serialization (also available outside Android). */
internal object JsonUtil {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun parse(text: String): JsonElement = json.parseToJsonElement(text)
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

internal fun JsonObject.str(key: String, default: String = ""): String {
    val v = this[key]
    return if (v is JsonPrimitive && v !is JsonNull) v.content else default
}

internal fun JsonObject.long(key: String, default: Long): Long =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: default

internal fun JsonObject.int(key: String, default: Int): Int = long(key, default.toLong()).toInt()

internal fun JsonObject.double(key: String, default: Double): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: default

internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull } ?: default

internal fun JsonObject.has(key: String): Boolean = this[key] != null && this[key] !is JsonNull

/** JSON string literal with escaping. */
internal fun jsonString(s: String): String = JsonPrimitive(s).toString()
