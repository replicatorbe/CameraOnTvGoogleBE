package be.cameratv.controller

// STUB : implémentation à venir.
object ExternalCommandParser {
    /** [command] = suffixe du topic après `<base>/cmd/` (show, grid, ptz, exit) ; [payload] = JSON. */
    fun parse(command: String, payload: String): ExternalCommand? = TODO()

    /** Lien profond `cameratv://show?camera=3&duration=30`, `cameratv://grid`. */
    fun fromUri(uri: String): ExternalCommand? = TODO()
}
