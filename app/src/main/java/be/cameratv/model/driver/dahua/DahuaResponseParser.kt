package be.cameratv.model.driver.dahua

import be.cameratv.model.Camera

/** Analyse des réponses texte `clé=valeur` des CGI Dahua. Fonctions pures, sans réseau. */
internal object DahuaResponseParser {

    private val TITLE_KEY = Regex("""^table\.ChannelTitle\[(\d+)]\.Name$""")
    private val STATE_KEY = Regex("""^states\[(\d+)]\.(channel|connectionState)$""")

    /** Paires clé/valeur dans l'ordre ; tolère `\r\n`, lignes vides et lignes sans `=`. */
    fun parseKeyValues(body: String): List<Pair<String, String>> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toList()

    /** Réponse de `getConfig&name=ChannelTitle` : l'index 0 correspond au canal 1. */
    fun parseChannelTitles(body: String): List<Camera> =
        parseKeyValues(body)
            .mapNotNull { (key, value) ->
                val index = TITLE_KEY.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val channel = index + 1
                Camera(channel = channel, name = value.ifBlank { "Caméra $channel" })
            }
            .distinctBy { it.channel }
            .sortedBy { it.channel }

    /**
     * Réponse de `getCameraState` : canaux (base 1) connectés, ou null si la réponse
     * ne contient aucun état exploitable.
     */
    fun parseConnectedChannels(body: String): Set<Int>? {
        val channels = mutableMapOf<Int, Int>()
        val states = mutableMapOf<Int, String>()
        for ((key, value) in parseKeyValues(body)) {
            val match = STATE_KEY.matchEntire(key) ?: continue
            val index = match.groupValues[1].toInt()
            when (match.groupValues[2]) {
                "channel" -> value.toIntOrNull()?.let { channels[index] = it }
                else -> states[index] = value
            }
        }
        val known = channels.keys.filter { it in states }
        if (known.isEmpty()) return null
        return known
            .filter { states[it].equals("Connected", ignoreCase = true) }
            .map { channels.getValue(it) + 1 }
            .toSet()
    }

    /** Les commandes (PTZ…) répondent `OK` en cas de succès. */
    fun isOk(body: String): Boolean = body.trim().equals("OK", ignoreCase = true)
}
