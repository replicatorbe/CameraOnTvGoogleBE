package be.cameratv.model

/** Une caméra exposée par le NVR. [channel] commence à 1, comme dans l'interface Dahua. */
data class Camera(
    val channel: Int,
    val name: String,
    val ptz: Boolean = false,
)

/** MAIN = flux principal (plein écran), SUB = flux secondaire basse résolution (grille). */
enum class StreamQuality { MAIN, SUB }

enum class PtzDirection { UP, DOWN, LEFT, RIGHT, ZOOM_IN, ZOOM_OUT }
