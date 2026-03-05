package com.beatlooper.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatlooper.model.Pad
import com.beatlooper.model.SoundType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Palette Studio ────────────────────────────────────
private val BG       = Color(0xFF080808)
private val SURFACE  = Color(0xFF141414)
private val SURFACE2 = Color(0xFF1E1E1E)
private val BORDER   = Color(0xFF222222)
private val CYAN     = Color(0xFF00E5FF)
private val CYAN_DIM = Color(0x2200E5FF)
private val DIM      = Color(0xFF555555)
private val MED      = Color(0xFFAAAAAA)
private val LIGHT    = Color(0xFFCCCCCC)
private val RED_BG   = Color(0xFFD32F2F)
private val GREEN_BG = Color(0xFF1B5E20)

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BG, surface = SURFACE, primary = CYAN,
                    onBackground = Color.White
                )
            ) { BeatLooperApp() }
        }
    }
}

// ═══════════════════════════════════════════════════════
// ROOT
// ═══════════════════════════════════════════════════════

@Composable
fun BeatLooperApp(vm: MainViewModel = viewModel()) {
    val pads            by vm.pads.collectAsState()
    val bpm             by vm.bpm.collectAsState()
    val currentBeat     by vm.currentBeat.collectAsState()
    val isLoopRecording by vm.isLoopRecording.collectAsState()
    val isLoopPlaying   by vm.isLoopPlaying.collectAsState()
    val metronomeOn     by vm.metronomeEnabled.collectAsState()
    val selectedPadId   by vm.selectedPadId.collectAsState()
    val isRecordingMic  by vm.isRecordingMic.collectAsState()
    val tapCount        by vm.tapCount.collectAsState()
    val message         by vm.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BG
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF111111), BG),
                        radius = 1400f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppHeader(currentBeat = currentBeat)
                BpmSection(
                    bpm = bpm, tapCount = tapCount, metronomeOn = metronomeOn,
                    onSetBpm = vm::setBpm, onInc = vm::incrementBpm, onDec = vm::decrementBpm,
                    onTap = vm::onTapTempo, onToggleMetronome = vm::toggleMetronome
                )
                PadGrid(
                    pads = pads, onTap = vm::onPadTap, onLongPress = vm::onPadLongPress,
                    onSpeedChange = vm::setPadSpeed, onPitchChange = vm::setPadPitch,
                    modifier = Modifier.weight(1f)
                )
                LooperBar(
                    isRecording = isLoopRecording, isPlaying = isLoopPlaying,
                    onStartRec = vm::startLoopRecord, onStopRec = vm::stopLoopRecord,
                    onStop = vm::stopLoop, onExport = vm::exportLoop
                )
            }
        }
    }

    selectedPadId?.let { padId ->
        PadConfigSheet(
            pad = pads[padId], isRecordingMic = isRecordingMic,
            onAssignPreset = { label, resId -> vm.assignPresetSound(padId, label, resId) },
            onStartMic = vm::startMicRecord,
            onStopMic = { vm.stopMicRecord(padId) },
            onToggleLoop = { vm.togglePadLoop(padId) },
            onSetSpeed = { vm.setPadSpeed(padId, it) },
            onSetPitch = { vm.setPadPitch(padId, it) },
            onDismiss = vm::closeDialog
        )
    }
}

// ═══════════════════════════════════════════════════════
// HEADER
// ═══════════════════════════════════════════════════════

@Composable
fun AppHeader(currentBeat: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        @OptIn(ExperimentalMaterial3Api::class)
        Text(
            "BeatLooper",
            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 0..3) BeatDot(isActive = currentBeat > 0 && (currentBeat % 4).toInt() == i)
        }
    }
}

@Composable
fun BeatDot(isActive: Boolean) {
    val color by animateColorAsState(
        targetValue = if (isActive) CYAN else Color(0xFF222222),
        animationSpec = tween(50), label = "dot"
    )
    val glow by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(80), label = "glow"
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .drawBehind {
                if (glow > 0f)
                    drawCircle(CYAN.copy(alpha = 0.35f * glow), radius = size.minDimension * 1.9f)
            }
            .background(color)
    )
}

// ═══════════════════════════════════════════════════════
// BPM SECTION
// ═══════════════════════════════════════════════════════

@Composable
fun BpmSection(
    bpm: Double, tapCount: Int, metronomeOn: Boolean,
    onSetBpm: (Double) -> Unit, onInc: () -> Unit, onDec: () -> Unit,
    onTap: () -> Unit, onToggleMetronome: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SURFACE)
            .border(1.dp, BORDER, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // ── Ligne BPM ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("BPM", color = DIM, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(28.dp))

                // −
                SmallIconBtn("−") { onDec() }

                // Affichage BPM
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SURFACE2)
                        .border(1.dp, BORDER, RoundedCornerShape(8.dp))
                        .pointerInput(Unit) { detectTapGestures(onTap = { showDialog = true }) }
                        .padding(vertical = 5.dp)
                        .drawBehind {
                            // Glow cyan sous la valeur
                            drawCircle(
                                CYAN.copy(alpha = 0.05f),
                                radius = size.minDimension * 2.8f,
                                center = Offset(size.width / 2, size.height / 2)
                            )
                        }
                ) {
                    Text(
                        "%.1f".format(bpm),
                        color = CYAN, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // +
                SmallIconBtn("+") { onInc() }

                // Métronome
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (metronomeOn) CYAN_DIM else SURFACE2)
                        .border(1.dp, if (metronomeOn) CYAN.copy(0.4f) else BORDER, RoundedCornerShape(8.dp))
                        .pointerInput(Unit) { detectTapGestures(onTap = { onToggleMetronome() }) }
                ) {
                    Text("♩", color = if (metronomeOn) CYAN else DIM, fontSize = 16.sp)
                }
            }

            // ── Slider ──
            Slider(
                value = bpm.toFloat(), onValueChange = { onSetBpm(it.toDouble()) },
                valueRange = 40f..300f,
                modifier = Modifier.fillMaxWidth().height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = CYAN, activeTrackColor = CYAN, inactiveTrackColor = SURFACE2
                )
            )

            // ── Tap Tempo ──
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SURFACE2)
                    .border(1.dp, BORDER, RoundedCornerShape(8.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
                    .padding(vertical = 9.dp)
            ) {
                Text(
                    text = if (tapCount < 2) "TAP TEMPO" else "TAP  ($tapCount taps)",
                    color = MED, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp
                )
            }
        }
    }

    if (showDialog) {
        BpmInputDialog(currentBpm = bpm, onConfirm = { onSetBpm(it); showDialog = false },
            onDismiss = { showDialog = false })
    }
}

@Composable
fun SmallIconBtn(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SURFACE2)
            .border(1.dp, BORDER, RoundedCornerShape(6.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
    ) {
        Text(label, color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun BpmInputDialog(currentBpm: Double, onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("%.0f".format(currentBpm)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SURFACE,
        shape = RoundedCornerShape(14.dp),
        title = { Text("Saisir le BPM", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 15.sp) },
        text = {
            @OptIn(ExperimentalMaterial3Api::class)
            OutlinedTextField(
                value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("BPM (40–300)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CYAN, focusedLabelColor = CYAN,
                    cursorColor = CYAN, focusedTextColor = CYAN
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { text.toDoubleOrNull()?.let(onConfirm) }) {
                Text("OK", color = CYAN, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = DIM, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

// ═══════════════════════════════════════════════════════
// GRILLE 4×4
// ═══════════════════════════════════════════════════════

@Composable
fun PadGrid(
    pads: List<Pad>, onTap: (Int) -> Unit, onLongPress: (Int) -> Unit,
    onSpeedChange: (Int, Float) -> Unit = { _, _ -> },
    onPitchChange: (Int, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4), modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(pads.size) { i ->
            PadButton(
                pad = pads[i],
                onTap = { onTap(i) },
                onLongPress = { onLongPress(i) },
                onSpeedChange = { onSpeedChange(i, it) },
                onPitchChange = { onPitchChange(i, it) }
            )
        }
    }
}

// ─── Seuil en pixels avant d'activer le mode geste ───────────────
private const val GESTURE_THRESHOLD_PX = 12f

@Composable
fun PadButton(
    pad: Pad,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSpeedChange: (Float) -> Unit = {},
    onPitchChange: (Float) -> Unit = {}
) {
    var pressed   by remember { mutableStateOf(false) }
    var triggered by remember { mutableStateOf(false) }
    // Geste en cours : "speed" | "pitch" | null
    var gestureMode by remember { mutableStateOf<String?>(null) }
    // Valeurs locales pour feedback visuel instantané (avant sync ViewModel)
    var localSpeed by remember(pad.id) { mutableStateOf(pad.speed) }
    var localPitch by remember(pad.id) { mutableStateOf(pad.pitch) }
    val scope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (pressed || triggered) 0.87f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f), label = "scale"
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (triggered) 1f else if (pressed || gestureMode != null) 0.55f else 0.8f,
        animationSpec = tween(55), label = "alpha"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (triggered) 0.65f else 0f,
        animationSpec = tween(90), label = "glow"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                if (glowAlpha > 0f) {
                    val expand = size.width * 0.18f
                    drawRoundRect(
                        color = pad.color.copy(alpha = 0.42f * glowAlpha),
                        topLeft = Offset(-expand, -expand),
                        size = Size(size.width + expand * 2, size.height + expand * 2),
                        cornerRadius = CornerRadius(32f)
                    )
                }
                // Indicateur de geste actif : barre bleue (speed) ou verte (pitch)
                if (gestureMode == "speed") {
                    val barW = size.width * ((localSpeed - 0.25f) / (4.0f - 0.25f))
                    drawRect(CYAN.copy(alpha = 0.5f), topLeft = Offset(0f, size.height - 5f),
                        size = Size(barW, 5f))
                } else if (gestureMode == "pitch") {
                    val norm = (localPitch + 24f) / 48f
                    val barW = size.width * norm
                    drawRect(Color(0xFF00E676).copy(alpha = 0.5f),
                        topLeft = Offset(0f, size.height - 5f), size = Size(barW, 5f))
                }
            }
            .background(pad.color.copy(alpha = bgAlpha))
            .border(
                width = if (pad.isLooping) 2.dp else 0.dp,
                color = Color.White.copy(alpha = 0.75f),
                shape = RoundedCornerShape(10.dp)
            )
            .pointerInput(pad.id) {
                // ── Gestes drag : horizontal = speed, vertical = pitch ──
                var startX = 0f; var startY = 0f
                var startSpeed = pad.speed; var startPitch = pad.pitch
                var gestureLocked = false

                this.awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        startX = down.position.x; startY = down.position.y
                        startSpeed = localSpeed; startPitch = localPitch
                        gestureMode = null; gestureLocked = false
                        pressed = true

                        var longPressJob = scope.launch {
                            delay(500)
                            if (gestureMode == null && !gestureLocked) {
                                pressed = false
                                onLongPress()
                            }
                        }

                        var upOrCancel = false
                        while (!upOrCancel) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                upOrCancel = true
                                longPressJob.cancel()
                                pressed = false
                                if (gestureMode == null) {
                                    // C'est un tap simple
                                    triggered = true; onTap()
                                    scope.launch { delay(160); triggered = false }
                                }
                                gestureMode = null
                                break
                            }
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY

                            if (gestureMode == null && (Math.abs(dx) > GESTURE_THRESHOLD_PX || Math.abs(dy) > GESTURE_THRESHOLD_PX)) {
                                gestureMode = if (Math.abs(dx) > Math.abs(dy)) "speed" else "pitch"
                                gestureLocked = true
                                longPressJob.cancel()
                                pressed = false
                            }

                            if (gestureMode == "speed") {
                                // Swipe horizontal : plage 0.25 – 4.0
                                // 1 px = 0.01× (300px pour aller de min à max)
                                val newSpeed = (startSpeed + dx * 0.012f).coerceIn(0.25f, 4.0f)
                                if (Math.abs(newSpeed - localSpeed) > 0.01f) {
                                    localSpeed = newSpeed
                                    onSpeedChange(newSpeed)
                                }
                                change.consume()
                            } else if (gestureMode == "pitch") {
                                // Swipe vertical : haut = aigu, bas = grave
                                // −24 à +24 → plage 48 demi-tons / 300px
                                val newPitch = (startPitch - dy * 0.16f).coerceIn(-24f, 24f)
                                if (Math.abs(newPitch - localPitch) > 0.1f) {
                                    localPitch = newPitch
                                    onPitchChange(newPitch)
                                }
                                change.consume()
                            }
                        }
                    }
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp)
        ) {
            if (pad.soundType != SoundType.NONE) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.5f)))
                Spacer(Modifier.height(2.dp))
            }
            Text(
                pad.label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                maxLines = 2, lineHeight = 13.sp
            )
            // Badges speed/pitch si modifiés
            val showBadges = (localSpeed != 1.0f || localPitch != 0.0f) && pad.soundType != SoundType.NONE
            if (showBadges) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (localSpeed != 1.0f)
                        PadBadge("×${"%.1f".format(localSpeed)}", CYAN)
                    if (localPitch != 0.0f)
                        PadBadge("${if (localPitch > 0) "+" else ""}${"%.0f".format(localPitch)}st",
                            Color(0xFF00E676))
                }
            }
            if (pad.isLooping) Text("⟳", color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
        }
    }
}

@Composable
fun PadBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.25f))
            .padding(horizontal = 3.dp, vertical = 1.dp)
    ) {
        @OptIn(ExperimentalMaterial3Api::class)
        Text(text, color = color, fontSize = 7.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold)
    }
}

// ═══════════════════════════════════════════════════════
// LOOPER BAR
// ═══════════════════════════════════════════════════════

@Composable
fun LooperBar(
    isRecording: Boolean, isPlaying: Boolean,
    onStartRec: () -> Unit, onStopRec: () -> Unit,
    onStop: () -> Unit, onExport: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        StudioBtn(
            text = if (isRecording) "⏹  STOP REC" else "⏺  REC",
            bg = if (isRecording) RED_BG else SURFACE,
            modifier = Modifier.weight(1.4f), onClick = if (isRecording) onStopRec else onStartRec
        )
        StudioBtn(text = "■  STOP", bg = SURFACE, modifier = Modifier.weight(0.9f), onClick = onStop)
        StudioBtn(
            text = "💾  EXPORT", bg = if (isPlaying) GREEN_BG else SURFACE,
            enabled = isPlaying, modifier = Modifier.weight(1.1f), onClick = onExport
        )
    }
}

@Composable
fun StudioBtn(text: String, bg: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val a = if (enabled) 1f else 0.35f
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg.copy(alpha = a))
            .border(1.dp, BORDER.copy(alpha = a), RoundedCornerShape(8.dp))
            .pointerInput(enabled) { if (enabled) detectTapGestures(onTap = { onClick() }) }
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        @OptIn(ExperimentalMaterial3Api::class)
        Text(text, color = Color.White.copy(alpha = a), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, letterSpacing = 0.4.sp, maxLines = 1)
    }
}

// ═══════════════════════════════════════════════════════
// CONFIG DIALOG
// ═══════════════════════════════════════════════════════

@Composable
fun PadConfigSheet(
    pad: Pad, isRecordingMic: Boolean,
    onAssignPreset: (String, Int) -> Unit,
    onStartMic: () -> Unit, onStopMic: () -> Unit,
    onToggleLoop: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetPitch: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf("Kick", "Snare", "Hi-Hat", "Clap", "Bass", "Tom", "Piano Do", "Piano Ré")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SURFACE,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(13.dp).clip(CircleShape).background(pad.color))
                @OptIn(ExperimentalMaterial3Api::class)
                Text("Configurer  ${pad.label}", color = Color.White,
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                // ── Sons préchargés ──────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    Text("SONS PRÉCHARGÉS", color = DIM, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    presets.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { label ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(SURFACE2)
                                        .border(1.dp, BORDER, RoundedCornerShape(7.dp))
                                        .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                                        .padding(vertical = 8.dp)
                                ) {
                                    @OptIn(ExperimentalMaterial3Api::class)
                                    Text("🎵  $label", color = LIGHT, fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }

                Divider(color = BORDER)

                // ── Vitesse ──────────────────────────────────────────────────
                // Indépendant du pitch — time-stretching via phase vocoder
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        @OptIn(ExperimentalMaterial3Api::class)
                        Text("VITESSE", color = DIM, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            @OptIn(ExperimentalMaterial3Api::class)
                            Text(
                                "×${"%.2f".format(pad.speed)}",
                                color = CYAN, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            if (pad.speed != 1.0f) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SURFACE2)
                                        .border(1.dp, BORDER, RoundedCornerShape(4.dp))
                                        .pointerInput(Unit) { detectTapGestures(onTap = { onSetSpeed(1.0f) }) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    @OptIn(ExperimentalMaterial3Api::class)
                                    Text("RESET", color = DIM, fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    // Slider speed : logarithmique pour une sensation naturelle
                    // On mappe 0.25–4.0 linéairement (log serait mieux mais complexe ici)
                    Slider(
                        value = pad.speed,
                        onValueChange = { onSetSpeed(it) },
                        valueRange = 0.25f..4.0f,
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = CYAN, activeTrackColor = CYAN,
                            inactiveTrackColor = SURFACE2
                        )
                    )
                    // Marqueurs de référence
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("×0.25", "×0.5", "×1", "×2", "×4").forEach { label ->
                            @OptIn(ExperimentalMaterial3Api::class)
                            Text(label, color = DIM, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Divider(color = BORDER)

                // ── Pitch ────────────────────────────────────────────────────
                // Indépendant de la vitesse — pitch-shifting via phase vocoder
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        @OptIn(ExperimentalMaterial3Api::class)
                        Text("PITCH", color = DIM, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            @OptIn(ExperimentalMaterial3Api::class)
                            Text(
                                "${if (pad.pitch >= 0) "+" else ""}${"%.1f".format(pad.pitch)} st",
                                color = Color(0xFF00E676), fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                            )
                            if (pad.pitch != 0.0f) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SURFACE2)
                                        .border(1.dp, BORDER, RoundedCornerShape(4.dp))
                                        .pointerInput(Unit) { detectTapGestures(onTap = { onSetPitch(0.0f) }) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    @OptIn(ExperimentalMaterial3Api::class)
                                    Text("RESET", color = DIM, fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    Slider(
                        value = pad.pitch,
                        onValueChange = { onSetPitch(it) },
                        valueRange = -24f..24f,
                        steps = 47, // 48 demi-tons = 47 steps pour snap on each semitone
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E676),
                            activeTrackColor = Color(0xFF00E676),
                            inactiveTrackColor = SURFACE2
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("−24", "−12", "0", "+12", "+24").forEach { label ->
                            @OptIn(ExperimentalMaterial3Api::class)
                            Text(label, color = DIM, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Divider(color = BORDER)

                // ── Micro ────────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    Text("MICRO", color = DIM, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isRecordingMic) RED_BG.copy(0.25f) else SURFACE2)
                            .border(1.dp, if (isRecordingMic) RED_BG.copy(0.7f) else BORDER, RoundedCornerShape(7.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { if (isRecordingMic) onStopMic() else onStartMic() })
                            }
                            .padding(vertical = 10.dp)
                    ) {
                        @OptIn(ExperimentalMaterial3Api::class)
                        Text(
                            text = if (isRecordingMic) "⏹  ARRÊTER" else "🎙  ENREGISTRER VIA MICRO",
                            color = if (isRecordingMic) RED_BG else LIGHT,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp
                        )
                    }
                }

                Divider(color = BORDER)

                // ── Mode boucle ──────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    Text("MODE BOUCLE", color = LIGHT, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    StudioSwitch(checked = pad.isLooping, onToggle = onToggleLoop)
                }
            }
        },
        confirmButton = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(SURFACE2)
                    .border(1.dp, BORDER, RoundedCornerShape(7.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                @OptIn(ExperimentalMaterial3Api::class)
                Text("FERMER", color = MED, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
        }
    )
}

@Composable
fun StudioSwitch(checked: Boolean, onToggle: () -> Unit) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "thumb"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) CYAN else SURFACE2, animationSpec = tween(200), label = "track"
    )
    Box(
        modifier = Modifier
            .width(44.dp).height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(trackColor)
            .border(1.dp, if (checked) CYAN.copy(0.4f) else BORDER, RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggle() }) }
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .offset(x = thumbOffset + 2.dp, y = 2.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
