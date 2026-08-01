package com.claude.callingai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import kotlin.math.sqrt
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

// ---------- Design tokens (matches the "quiet line" look) ----------
private val BgColor = Color(0xFF14202B)
private val PanelColor = Color(0xFF1B2A38)
private val LineColor = Color(0xFF26394A)
private val GoldColor = Color(0xFFC9A24B)
private val CreamColor = Color(0xFFF2ECDD)
private val MutedColor = Color(0xFF5C7085)
private val ListenColor = Color(0xFF7FA6C9)
private val EndCallColor = Color(0xFFC0453F)

enum class CallStatus { IDLE, LISTENING, THINKING, SPEAKING }
data class Turn(val role: String, val content: String)

class MainActivity : ComponentActivity(), SensorEventListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ---------- Shake detection ----------
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeTime = 0L
    private val shakeThreshold = 14f // in units of g; higher = needs a harder shake

    private var callActive = false
    private var muted = false

    // Mutable state read by Compose
    private val status = mutableStateOf(CallStatus.IDLE)
    private val transcript = mutableStateOf(listOf<Turn>())
    private val seconds = mutableStateOf(0)
    private val liveWords = mutableStateOf("")
    private val micGranted = mutableStateOf(false)

    private var history = mutableListOf<Turn>()
    private var timerJob: Job? = null

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted.value = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        micGranted.value = ContextCompat_checkSelfPermission(this)

        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            CallScreen(
                status = status.value,
                transcript = transcript.value,
                seconds = seconds.value,
                liveWords = liveWords.value,
                micGranted = micGranted.value,
                muted = muted,
                onRequestMic = { requestMicPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onStartCall = { startCall() },
                onEndCall = { endCall() },
                onToggleMute = { toggleMute() }
            )
        }
    }

    private fun ContextCompat_checkSelfPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    // ---------- Call lifecycle ----------

    private fun startCall() {
        if (!micGranted.value) return
        callActive = true
        muted = false
        history = mutableListOf()
        transcript.value = emptyList()
        seconds.value = 0
        startTimer()

        status.value = CallStatus.SPEAKING
        val greeting = "Yo, what's good? What's on your mind?"
        history.add(Turn("assistant", greeting))
        transcript.value = history.toList()
        speak(greeting) {
            if (callActive) {
                status.value = CallStatus.LISTENING
                startListening()
            }
        }
    }

    private fun endCall() {
        callActive = false
        stopTimer()
        status.value = CallStatus.IDLE
        liveWords.value = ""
        speechRecognizer?.cancel()
        tts?.stop()
    }

    private fun toggleMute() {
        muted = !muted
        if (muted) {
            speechRecognizer?.cancel()
        } else if (status.value == CallStatus.LISTENING) {
            startListening()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                seconds.value += 1
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // ---------- Speech recognition ----------

    private fun startListening() {
        if (!callActive || muted) return
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    liveWords.value = list?.firstOrNull() ?: ""
                }

                override fun onResults(bundleResults: Bundle?) {
                    liveWords.value = ""
                    val list = bundleResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        handleUserSpeech(text)
                    } else if (callActive && !muted) {
                        // nothing heard, keep listening
                        startListening()
                    }
                }

                override fun onError(error: Int) {
                    // Common on silence timeout; just keep the call open and re-listen.
                    if (callActive && !muted && status.value == CallStatus.LISTENING) {
                        startListening()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = RecognizerIntent.getVoiceInputIntent(this@MainActivity).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            }
            startListening(intent)
        }
    }

    // ---------- Talking to Claude ----------

    private fun handleUserSpeech(text: String) {
        if (!callActive) return
        speechRecognizer?.cancel()
        status.value = CallStatus.THINKING
        history.add(Turn("user", text))
        transcript.value = history.toList()

        scope.launch {
            val reply = try {
                withContext(Dispatchers.IO) { callClaude(history) }
            } catch (e: Exception) {
                "Sorry, the line dropped for a second. Go ahead."
            }
            history.add(Turn("assistant", reply))
            transcript.value = history.toList()
            if (!callActive) return@launch
            status.value = CallStatus.SPEAKING
            speak(reply) {
                if (callActive) {
                    status.value = CallStatus.LISTENING
                    startListening()
                }
            }
        }
    }

    private fun callClaude(turns: List<Turn>): String {
        val apiKey = ApiKeyStore.get(this)
        if (apiKey.isNullOrBlank()) {
            return "No API key is set. Add one in settings to keep talking."
        }

        val messages = JSONArray()
        turns.forEach { t ->
            messages.put(JSONObject().put("role", t.role).put("content", t.content))
        }

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 300)
            put(
                "system",
                "You are on a live voice call with the user, talking like a Gen Z / Gen " +
                    "Alpha friend — casual, current slang (no cap, fr, bet, lowkey/highkey, " +
                    "it's giving ___, rizz, bussin, slay, mid, etc.) woven in naturally, not " +
                    "every sentence stuffed with it. Reply the way someone would actually " +
                    "speak out loud: short, casual, conversational. No markdown, no lists, " +
                    "no headers, no asterisks. Get to the point in 1-3 sentences unless asked " +
                    "for more. Keep slang natural and current, not forced or cringe, and drop " +
                    "it entirely for serious topics (health, safety, anything heavy) where " +
                    "being clear matters more than sounding cool."
            )
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val content = json.optJSONArray("content") ?: return "Sorry, I didn't catch that."
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") return block.optString("text")
            }
            return "Sorry, I didn't catch that."
        }
    }

    private fun speak(text: String, onDone: () -> Unit) {
        val id = "utt-${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                scope.launch { onDone() }
            }
            @Deprecated("legacy")
            override fun onError(utteranceId: String?) {
                scope.launch { onDone() }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // ---------- Shake to activate ----------

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val gX = event.values[0] / SensorManager.GRAVITY_EARTH
        val gY = event.values[1] / SensorManager.GRAVITY_EARTH
        val gZ = event.values[2] / SensorManager.GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

        if (gForce > shakeThreshold) {
            val now = System.currentTimeMillis()
            // Debounce so one shake doesn't fire a dozen times.
            if (now - lastShakeTime < 1200) return
            lastShakeTime = now
            onShakeDetected()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onShakeDetected() {
        when (status.value) {
            CallStatus.IDLE -> {
                if (micGranted.value) {
                    startCall()
                } else {
                    Toast.makeText(this, "Allow the mic first, then shake to call", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                // Already on a call — a shake hangs up.
                endCall()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callActive = false
        stopTimer()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}

// ---------- Simple API key storage (SharedPreferences — not encrypted; see README) ----------
object ApiKeyStore {
    private const val PREFS = "calling_ai_prefs"
    private const val KEY = "anthropic_api_key"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }
}

// ---------- Compose UI ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(
    status: CallStatus,
    transcript: List<Turn>,
    seconds: Int,
    liveWords: String,
    micGranted: Boolean,
    muted: Boolean,
    onRequestMic: () -> Unit,
    onStartCall: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf(ApiKeyStore.get(context) ?: "") }

    val orbColor = when (status) {
        CallStatus.SPEAKING -> GoldColor
        CallStatus.LISTENING -> ListenColor
        else -> MutedColor
    }

    val infinite = rememberInfiniteTransition(label = "breathe")
    val breathe by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheScale"
    )

    Surface(color = BgColor) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Header
            Column(modifier = Modifier.padding(24.dp, 24.dp, 24.dp, 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "A QUIET LINE, OPEN",
                            color = GoldColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "Call Claude",
                            color = CreamColor,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    TextButton(onClick = { showSettings = true }) {
                        Text("Settings", color = MutedColor, fontSize = 13.sp)
                    }
                }
            }

            if (!micGranted) {
                Box(
                    modifier = Modifier
                        .padding(24.dp, 8.dp)
                        .fillMaxWidth()
                        .background(Color(0xFF2A1E1E), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF5C3A3A), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            "Microphone access is needed to make a call.",
                            color = Color(0xFFE8C7C7),
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRequestMic, colors = ButtonDefaults.buttonColors(containerColor = GoldColor)) {
                            Text("Allow microphone", color = BgColor)
                        }
                    }
                }
            }

            // Orb + status
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp, 20.dp, 24.dp, 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(if (status != CallStatus.IDLE) breathe else 1f)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(orbColor.copy(alpha = 0.35f), PanelColor)
                            ),
                            shape = CircleShape
                        )
                        .border(1.dp, orbColor, CircleShape)
                )
                Spacer(Modifier.height(14.dp))
                val label = when (status) {
                    CallStatus.IDLE -> "Not on a call"
                    CallStatus.LISTENING -> "Listening"
                    CallStatus.THINKING -> "Thinking"
                    CallStatus.SPEAKING -> "Speaking"
                }
                val mm = seconds / 60
                val ss = seconds % 60
                Text(
                    if (status != CallStatus.IDLE) "$label · ${mm}:${ss.toString().padStart(2, '0')}" else label,
                    color = MutedColor,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp
                )
                if (status == CallStatus.IDLE) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "shake your phone to call",
                        color = MutedColor.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }

            // Transcript
            val listState = rememberLazyListState()
            LaunchedEffect(transcript.size, liveWords) {
                if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp, 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(transcript) { turn ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (turn.role == "user") Alignment.End else Alignment.Start
                    ) {
                        Text(
                            if (turn.role == "user") "YOU" else "CLAUDE",
                            color = MutedColor,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                        Text(
                            turn.content,
                            color = CreamColor.copy(alpha = 0.85f),
                            fontSize = 14.sp
                        )
                    }
                }
                if (liveWords.isNotEmpty()) {
                    item {
                        Text(
                            liveWords,
                            color = MutedColor,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.dp, LineColor)
                    .padding(24.dp, 16.dp, 24.dp, 32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status == CallStatus.IDLE) {
                    FilledIconButton(
                        onClick = onStartCall,
                        enabled = micGranted,
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = GoldColor)
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "Start call", tint = BgColor)
                    }
                } else {
                    FilledIconButton(
                        onClick = onToggleMute,
                        modifier = Modifier.size(52.dp).padding(end = 20.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (muted) GoldColor else PanelColor
                        )
                    ) {
                        Icon(
                            if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (muted) "Unmute" else "Mute",
                            tint = if (muted) BgColor else CreamColor
                        )
                    }
                    FilledIconButton(
                        onClick = onEndCall,
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = EndCallColor)
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "End call", tint = CreamColor)
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Anthropic API key") },
            text = {
                Column {
                    Text(
                        "Stored locally on this device only. Anyone with access to this " +
                            "phone/app could retrieve it, so don't ship this build to others.",
                        fontSize = 12.sp,
                        color = MutedColor
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("sk-ant-...") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ApiKeyStore.set(context, keyInput.trim())
                    showSettings = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("Cancel") }
            }
        )
    }
}
