package be.cameratv.controller

import be.cameratv.model.PtzDirection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLDecoder

/**
 * Traduit les messages de la domotique (topic MQTT + charge utile) et les liens profonds en
 * [ExternalCommand]. Tolérant : un message invalide donne null, jamais d'exception.
 */
object ExternalCommandParser {

    private const val URI_SCHEME = "cameratv"
    private const val DEFAULT_MOVE_MS = 500L
    private const val MIN_MOVE_MS = 100L
    private const val MAX_MOVE_MS = 10_000L

    /** [command] = suffixe du topic après `<base>/cmd/` (show, grid, ptz, exit) ; [payload] = JSON. */
    fun parse(command: String, payload: String): ExternalCommand? = try {
        when (command.trim().lowercase()) {
            "show" -> parseShow(payload)
            "grid" -> ExternalCommand.ShowGrid
            "ptz" -> parsePtz(parseJson(payload) as? JsonObject)
            "exit" -> ExternalCommand.Exit
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Lien profond `cameratv://show?camera=3&duration=30`, `cameratv://grid`.
     * Accepte aussi `cameratv://ptz?camera=5&action=left&value=800` et `cameratv://exit`.
     */
    fun fromUri(uri: String): ExternalCommand? = try {
        val parsed = URI(uri.trim())
        if (!parsed.scheme.equals(URI_SCHEME, ignoreCase = true)) {
            null
        } else {
            val command = parsed.host
                ?: parsed.path?.trim('/')?.takeIf { it.isNotEmpty() }
            command?.let { parse(it, queryToJson(parsed.rawQuery).toString()) }
        }
    } catch (e: Exception) {
        null
    }

    // --- Commandes ---------------------------------------------------------------------------

    private fun parseShow(payload: String): ExternalCommand? {
        val element = parseJson(payload)
        if (element is JsonObject) {
            val camera = cameraRef(element["camera"]) ?: return null
            val duration = intOf(element["duration"])?.takeIf { it > 0 }
            return ExternalCommand.ShowCamera(camera, duration)
        }
        // Charge utile nue : `3` ou `OUESTPTZ` (avec ou sans guillemets JSON).
        val camera = when {
            element is JsonPrimitive && element.isString -> refFromText(element.content)
            element != null && element !is JsonPrimitive -> null // Tableau JSON.
            else -> bareRef(payload.trim())
        }
        return camera?.let { ExternalCommand.ShowCamera(it) }
    }

    /** Texte brut : numéro de canal ou nom ; ni JSON mal formé, ni booléen, ni nombre non entier. */
    private fun bareRef(text: String): CameraRef? = when {
        text.isEmpty() || text.startsWith("{") || text.startsWith("[") -> null
        text == "true" || text == "false" || text == "null" -> null
        text.toDoubleOrNull() != null -> integralOf(text)?.takeIf { it > 0 }?.let { CameraRef.ByChannel(it) }
        else -> CameraRef.ByName(text)
    }

    private fun parsePtz(json: JsonObject?): ExternalCommand? {
        json ?: return null
        val camera = cameraRef(json["camera"]) ?: return null
        val action = (json["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.trim()?.lowercase()?.replace('-', '_')
            ?: return null
        val value = intOf(json["value"])
        val ptzAction = when (action) {
            "preset" -> value?.takeIf { it > 0 }?.let { PtzAction.Preset(it) } ?: return null
            "stop" -> PtzAction.Stop
            else -> {
                val direction = directionOf(action) ?: return null
                val duration = (value?.toLong() ?: DEFAULT_MOVE_MS).coerceIn(MIN_MOVE_MS, MAX_MOVE_MS)
                PtzAction.Move(direction, duration)
            }
        }
        return ExternalCommand.Ptz(camera, ptzAction)
    }

    private fun directionOf(action: String): PtzDirection? = when (action) {
        "left" -> PtzDirection.LEFT
        "right" -> PtzDirection.RIGHT
        "up" -> PtzDirection.UP
        "down" -> PtzDirection.DOWN
        "zoom_in" -> PtzDirection.ZOOM_IN
        "zoom_out" -> PtzDirection.ZOOM_OUT
        else -> null
    }

    // --- Valeurs -----------------------------------------------------------------------------

    /** Null si la charge utile n'est pas du JSON (elle est alors traitée comme du texte brut). */
    private fun parseJson(payload: String): JsonElement? = try {
        Json.parseToJsonElement(payload)
    } catch (e: Exception) {
        null
    }

    /** Numéro de canal (nombre ou texte numérique) ou nom ; null pour tout le reste. */
    private fun cameraRef(element: JsonElement?): CameraRef? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return refFromText(primitive.content)
        return integralOf(primitive.content)?.takeIf { it > 0 }?.let { CameraRef.ByChannel(it) }
    }

    private fun refFromText(text: String): CameraRef? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { return if (it > 0) CameraRef.ByChannel(it) else null }
        return CameraRef.ByName(trimmed)
    }

    /** Entier donné en nombre JSON (3 ou 3.0) ou en texte ("3"). */
    private fun intOf(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.let { integralOf(it.trim()) }
    }

    private fun integralOf(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        val number = text.toDoubleOrNull() ?: return null
        if (number % 1.0 != 0.0 || number !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) return null
        return number.toInt()
    }

    /** `a=1&b=x%20y` → objet JSON dont toutes les valeurs sont des textes. */
    private fun queryToJson(rawQuery: String?): JsonObject {
        if (rawQuery.isNullOrEmpty()) return JsonObject(emptyMap())
        val values = rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val separator = pair.indexOf('=')
                val key = if (separator < 0) pair else pair.substring(0, separator)
                val value = if (separator < 0) "" else pair.substring(separator + 1)
                decode(key) to JsonPrimitive(decode(value))
            }
        return JsonObject(values)
    }

    private fun decode(text: String): String = URLDecoder.decode(text, Charsets.UTF_8.name())
}
