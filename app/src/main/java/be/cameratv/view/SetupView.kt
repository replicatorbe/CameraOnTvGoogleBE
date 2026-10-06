package be.cameratv.view

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import be.cameratv.controller.AppController
import be.cameratv.model.AppState
import be.cameratv.model.NvrConfig

/**
 * Formulaire de configuration du NVR. Ici, la navigation utilise le focus Compose normal
 * (le contrôleur ne reçoit pas les flèches sur cet écran).
 */
@Composable
fun SetupView(state: AppState, controller: AppController) {
    val initial = state.config
    // Brouillon du formulaire : état purement visuel, réinitialisé si la config du modèle change.
    var host by rememberSaveable(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var username by rememberSaveable(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by rememberSaveable(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var httpPort by rememberSaveable(initial) { mutableStateOf((initial?.httpPort ?: 80).toString()) }
    var rtspPort by rememberSaveable(initial) { mutableStateOf((initial?.rtspPort ?: 554).toString()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstField.requestFocus() }

    fun submit() {
        val http = httpPort.trim().toIntOrNull()
        val rtsp = rtspPort.trim().toIntOrNull()
        validationError = when {
            host.isBlank() -> "L'adresse du NVR est obligatoire."
            http == null || http !in 1..65535 -> "Le port HTTP doit être un nombre entre 1 et 65535."
            rtsp == null || rtsp !in 1..65535 -> "Le port RTSP doit être un nombre entre 1 et 65535."
            else -> null
        }
        if (validationError == null) {
            controller.submitSetup(
                NvrConfig(
                    host = host.trim(),
                    username = username.trim(),
                    password = password,
                    httpPort = http!!,
                    rtspPort = rtsp!!,
                )
            )
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .padding(vertical = 24.dp)
                .background(CameraTvColors.Surface, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Configuration du NVR",
                color = CameraTvColors.Text,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
            )

            FormField(
                label = "Adresse IP ou nom d'hôte",
                value = host,
                onValueChange = { host = it },
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.focusRequester(firstField),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                FormField(
                    label = "Utilisateur",
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.weight(1f),
                )
                FormField(
                    label = "Mot de passe",
                    value = password,
                    onValueChange = { password = it },
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                FormField(
                    label = "Port HTTP",
                    value = httpPort,
                    onValueChange = { httpPort = it.filter(Char::isDigit).take(5) },
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                FormField(
                    label = "Port RTSP",
                    value = rtspPort,
                    onValueChange = { rtspPort = it.filter(Char::isDigit).take(5) },
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    onDone = ::submit,
                    modifier = Modifier.weight(1f),
                )
            }

            val error = validationError ?: state.error
            if (error != null) {
                Text(error, color = CameraTvColors.Error, fontSize = 20.sp)
            }

            Button(
                onClick = ::submit,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Connexion", fontSize = 22.sp)
            }
        }
    }
}

/** Champ texte « TV » : libellé au-dessus, gros texte, bordure accentuée quand il a le focus. */
@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            color = if (focused) CameraTvColors.Accent else CameraTvColors.TextMuted,
            fontSize = 18.sp,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = CameraTvColors.Text, fontSize = 24.sp),
            cursorBrush = SolidColor(CameraTvColors.Accent),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                onDone = { onDone() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                // Flèches haut/bas : passer au champ voisin (sinon le champ garde le focus).
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key.nativeKeyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> focusManager.moveFocus(FocusDirection.Down)
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                }
                .background(
                    if (focused) CameraTvColors.SurfaceVariant else Color.Black.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp),
                )
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) CameraTvColors.Accent else CameraTvColors.TextMuted.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
