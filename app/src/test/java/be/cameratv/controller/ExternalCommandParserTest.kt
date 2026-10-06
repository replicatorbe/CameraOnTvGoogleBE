package be.cameratv.controller

import be.cameratv.controller.CameraRef.ByChannel
import be.cameratv.controller.CameraRef.ByName
import be.cameratv.controller.ExternalCommand.Exit
import be.cameratv.controller.ExternalCommand.Ptz
import be.cameratv.controller.ExternalCommand.ShowCamera
import be.cameratv.controller.ExternalCommand.ShowGrid
import be.cameratv.model.PtzDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalCommandParserTest {

    private fun assertParses(expected: Map<Pair<String, String>, ExternalCommand?>) {
        for ((input, command) in expected) {
            assertEquals("${input.first} ${input.second}", command, ExternalCommandParser.parse(input.first, input.second))
        }
    }

    @Test
    fun `show, formes valides`() = assertParses(
        mapOf(
            ("show" to """{"camera": 3, "duration": 30}""") to ShowCamera(ByChannel(3), 30),
            ("show" to """{"camera": 3}""") to ShowCamera(ByChannel(3)),
            ("show" to """{"camera": "3", "duration": "15"}""") to ShowCamera(ByChannel(3), 15),
            ("show" to """{"camera": 3.0}""") to ShowCamera(ByChannel(3)),
            ("show" to """{"camera": "OUESTPTZ"}""") to ShowCamera(ByName("OUESTPTZ")),
            ("show" to """{"camera": "  Porte entrée "}""") to ShowCamera(ByName("Porte entrée")),
            ("show" to """{"camera": 2, "duration": 0}""") to ShowCamera(ByChannel(2)),
            ("show" to """{"camera": 2, "duration": -5}""") to ShowCamera(ByChannel(2)),
            ("show" to """{"camera": 2, "duration": "bientôt"}""") to ShowCamera(ByChannel(2)),
            ("show" to "3") to ShowCamera(ByChannel(3)),
            ("show" to " 3\n") to ShowCamera(ByChannel(3)),
            ("show" to "\"3\"") to ShowCamera(ByChannel(3)),
            ("show" to "OUESTPTZ") to ShowCamera(ByName("OUESTPTZ")),
            ("show" to "\"OUESTPTZ\"") to ShowCamera(ByName("OUESTPTZ")),
            ("show" to "Porte entrée") to ShowCamera(ByName("Porte entrée")),
            (" SHOW " to "3") to ShowCamera(ByChannel(3)),
        ),
    )

    @Test
    fun `show, formes invalides`() = assertParses(
        mapOf(
            ("show" to "") to null,
            ("show" to "   ") to null,
            ("show" to "0") to null,
            ("show" to "-1") to null,
            ("show" to "true") to null,
            ("show" to "null") to null,
            ("show" to "[3]") to null,
            ("show" to "{}") to null,
            ("show" to """{"camera": null}""") to null,
            ("show" to """{"camera": true}""") to null,
            ("show" to """{"camera": 2.5}""") to null,
            ("show" to """{"camera": ""}""") to null,
            ("show" to """{"camera": {"id": 3}}""") to null,
            ("show" to """{"camera": 3""") to null,
        ),
    )

    @Test
    fun `grid et exit acceptent n'importe quelle charge utile`() = assertParses(
        mapOf(
            ("grid" to "") to ShowGrid,
            ("grid" to "{}") to ShowGrid,
            ("grid" to "n'importe quoi") to ShowGrid,
            ("exit" to "") to Exit,
            ("exit" to "1") to Exit,
        ),
    )

    @Test
    fun `ptz, formes valides`() = assertParses(
        mapOf(
            ("ptz" to """{"camera": 5, "action": "left", "value": 800}""") to
                Ptz(ByChannel(5), PtzAction.Move(PtzDirection.LEFT, 800)),
            ("ptz" to """{"camera": 5, "action": "right"}""") to
                Ptz(ByChannel(5), PtzAction.Move(PtzDirection.RIGHT, 500)),
            ("ptz" to """{"camera": "5", "action": "UP", "value": "300"}""") to
                Ptz(ByChannel(5), PtzAction.Move(PtzDirection.UP, 300)),
            ("ptz" to """{"camera": 5, "action": "down", "value": 10}""") to
                Ptz(ByChannel(5), PtzAction.Move(PtzDirection.DOWN, 100)),
            ("ptz" to """{"camera": 5, "action": "zoom_in", "value": 60000}""") to
                Ptz(ByChannel(5), PtzAction.Move(PtzDirection.ZOOM_IN, 10_000)),
            ("ptz" to """{"camera": "OUESTPTZ", "action": "zoom-out"}""") to
                Ptz(ByName("OUESTPTZ"), PtzAction.Move(PtzDirection.ZOOM_OUT, 500)),
            ("ptz" to """{"camera": 5, "action": "preset", "value": 2}""") to
                Ptz(ByChannel(5), PtzAction.Preset(2)),
            ("ptz" to """{"camera": 5, "action": "stop"}""") to
                Ptz(ByChannel(5), PtzAction.Stop),
        ),
    )

    @Test
    fun `ptz, formes invalides`() = assertParses(
        mapOf(
            ("ptz" to "") to null,
            ("ptz" to "5") to null,
            ("ptz" to """{"action": "left"}""") to null,
            ("ptz" to """{"camera": 5}""") to null,
            ("ptz" to """{"camera": 5, "action": "spin"}""") to null,
            ("ptz" to """{"camera": 5, "action": 3}""") to null,
            ("ptz" to """{"camera": 5, "action": "preset"}""") to null,
            ("ptz" to """{"camera": 5, "action": "preset", "value": 0}""") to null,
            ("ptz" to """{"camera": 5, "action": "preset", "value": "x"}""") to null,
            ("ptz" to """{"camera": 0, "action": "stop"}""") to null,
        ),
    )

    @Test
    fun `commande inconnue`() = assertParses(
        mapOf(
            ("reboot" to "") to null,
            ("" to "3") to null,
        ),
    )

    @Test
    fun `liens profonds valides`() {
        val expected = mapOf(
            "cameratv://show?camera=3&duration=30" to ShowCamera(ByChannel(3), 30),
            "cameratv://show?camera=3" to ShowCamera(ByChannel(3)),
            "cameratv://show?camera=OUESTPTZ" to ShowCamera(ByName("OUESTPTZ")),
            "cameratv://show?camera=Porte%20entr%C3%A9e" to ShowCamera(ByName("Porte entrée")),
            "cameratv://show?camera=Porte+jardin&duration=" to ShowCamera(ByName("Porte jardin")),
            "cameratv://show?duration=10&camera=2&inconnu=1" to ShowCamera(ByChannel(2), 10),
            "CAMERATV://SHOW?camera=1" to ShowCamera(ByChannel(1)),
            "cameratv://grid" to ShowGrid,
            "cameratv://grid/" to ShowGrid,
            "cameratv://exit" to Exit,
            "cameratv://ptz?camera=5&action=preset&value=4" to Ptz(ByChannel(5), PtzAction.Preset(4)),
        )
        for ((uri, command) in expected) assertEquals(uri, command, ExternalCommandParser.fromUri(uri))
    }

    @Test
    fun `liens profonds invalides`() {
        val invalid = listOf(
            "",
            "cameratv://",
            "cameratv://show",
            "cameratv://show?camera=",
            "cameratv://show?camera=0",
            "cameratv://reboot",
            "https://example.com/show?camera=3",
            "cameratv://show?camera=%ZZ",
            "cameratv://show?camera=a b",
            "pas une uri",
        )
        for (uri in invalid) assertEquals(uri, null, ExternalCommandParser.fromUri(uri))
    }
}
