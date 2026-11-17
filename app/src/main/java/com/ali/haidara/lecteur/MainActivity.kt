package com.ali.haidara.lecteur

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale

private val Navy = Color(0xFF0F1B3D)
private val CardBlue = Color(0xFF1B2A5C)
private val Orange = Color(0xFFFFA726)
private val Teal = Color(0xFF26C6DA)
private val Violet = Color(0xFFB388FF)
private val Soft = Color(0xFFB0BEE8)

enum class Screen { HOME, TEXT, READER, EXO, QUIZ }

/** Une question, sa réponse, et (pour la dictée) le texte à faire écouter. */
data class QA(val q: String, val a: String, val say: String? = null)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var voiceStatus by mutableIntStateOf(0) // 0 en attente, 1 prête, 2 voix française manquante
    private var screen by mutableStateOf(Screen.HOME)
    private var busy by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    // Lecture
    private var fieldValue by mutableStateOf(TextFieldValue(""))
    private var sourceText by mutableStateOf("")
    private var mode by mutableIntStateOf(0) // 1, 2, 3 mots ou 0 = phrase
    private var chunks by mutableStateOf(listOf<String>())
    private var index by mutableIntStateOf(0)
    private var rawText = ""
    private var pendingUri: Uri? = null

    // Exercices
    private var qaList by mutableStateOf(listOf<QA>())
    private var quizItems by mutableStateOf(listOf<QA>())
    private var quizIndex by mutableIntStateOf(0)
    private var quizAnswer by mutableStateOf("")
    private var quizResult by mutableStateOf<Boolean?>(null)
    private var quizScore by mutableIntStateOf(0)
    private var quizDone by mutableStateOf(false)
    private var quizFrom = Screen.EXO

    private lateinit var prefs: SharedPreferences

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingUri
        if (ok && u != null) process(u, false)
    }
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) process(uri, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        prefs = getSharedPreferences("ali", Context.MODE_PRIVATE)
        qaList = loadQa()
        tts = TextToSpeech(this, this)
        setContent { App() }
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) { voiceStatus = 2; return }
        val r = t.setLanguage(Locale.FRANCE)
        voiceStatus = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) 2 else 1
        t.setSpeechRate(prefs.getFloat("rate", 0.85f))
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { runOnUiThread { speaking = true } }
            override fun onDone(id: String?) { runOnUiThread { speaking = false } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { runOnUiThread { speaking = false } }
        })
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }

    // ---------- Voix ----------
    private fun say(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u") }
    private fun speakCurrent() { chunks.getOrNull(index)?.let { say(it) } }
    private fun stopSpeaking() { tts?.stop(); speaking = false }

    // ---------- Navigation ----------
    private fun goBack() {
        stopSpeaking()
        screen = when (screen) {
            Screen.QUIZ -> quizFrom
            Screen.READER -> Screen.TEXT
            else -> Screen.HOME
        }
    }

    private fun newBook() {
        stopSpeaking(); rawText = ""; chunks = emptyList(); sourceText = ""
        fieldValue = TextFieldValue(""); index = 0; message = null; screen = Screen.HOME
    }

    // ---------- Photo / PDF ----------
    private fun openCamera() {
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val f = File(dir, "p_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        pendingUri = uri
        takePicture.launch(uri)
    }

    private fun process(uri: Uri, isPdf: Boolean) {
        busy = true; message = null
        lifecycleScope.launch {
            val text = try {
                if (isPdf) Ocr.fromPdf(this@MainActivity, uri) else Ocr.fromImage(this@MainActivity, uri)
            } catch (e: Exception) { "" }
            if (text.isBlank()) {
                message = "Je n'ai pas réussi à lire le texte. Essaie avec une photo plus nette, bien éclairée."
                busy = false
                return@launch
            }
            rawText = (rawText + "\n\n" + text).trim()
            val key = prefs.getString("groq", "") ?: ""
            if (key.isNotBlank() && rawText.length <= 6000) {
                Groq.correct(key, rawText)?.let { rawText = it }
            }
            fieldValue = TextFieldValue(rawText)
            screen = Screen.TEXT
            busy = false
        }
    }

    // ---------- Lecture par morceaux ----------
    private fun startReading() {
        val v = fieldValue
        val sel = v.selection
        val src = if (!sel.collapsed)
            v.text.substring(sel.min.coerceAtLeast(0), sel.max.coerceAtMost(v.text.length))
        else v.text
        if (src.isBlank()) { message = "Il n'y a pas de texte à lire."; return }
        sourceText = src
        rechunk()
    }

    private fun rechunk() {
        stopSpeaking()
        chunks = Chunker.make(sourceText, mode)
        index = 0
        if (chunks.isEmpty()) { message = "Il n'y a pas de texte à lire."; screen = Screen.TEXT } else screen = Screen.READER
    }

    // ---------- Exercices ----------
    private fun loadQa(): List<QA> = try {
        val arr = JSONArray(prefs.getString("qa", "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); QA(o.getString("q"), o.getString("a")) }
    } catch (e: Exception) { emptyList() }

    private fun saveQa() {
        val arr = JSONArray()
        qaList.forEach { arr.put(JSONObject().put("q", it.q).put("a", it.a)) }
        prefs.edit().putString("qa", arr.toString()).apply()
    }

    private fun startQuiz(items: List<QA>, from: Screen) {
        if (items.isEmpty()) return
        stopSpeaking()
        quizItems = items; quizIndex = 0; quizAnswer = ""; quizResult = null
        quizScore = 0; quizDone = false; quizFrom = from; screen = Screen.QUIZ
    }

    private fun startDictee() {
        startQuiz(chunks.take(30).map { QA("Écoute bien, puis écris ce que tu entends.", it, say = it) }, Screen.READER)
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    private fun isCorrect(given: String, expected: String): Boolean {
        val g = norm(given)
        if (g.isEmpty()) return false
        return expected.split("/", ";").map { norm(it) }.filter { it.isNotEmpty() }
            .any { e -> g == e || (e.length >= 6 && lev(g, e) <= 1) }
    }

    private fun checkAnswer() {
        val item = quizItems[quizIndex]
        val ok = isCorrect(quizAnswer, item.a)
        quizResult = ok
        if (ok) quizScore++
        say(if (ok) "Bravo, c'est juste !" else "Ce n'est pas ça. La bonne réponse est : ${item.a.substringBefore("/")}")
    }

    private fun nextQuestion() {
        stopSpeaking()
        if (quizIndex >= quizItems.lastIndex) quizDone = true
        else { quizIndex++; quizAnswer = ""; quizResult = null }
    }

    // ---------- Écrans ----------
    @Composable
    private fun App() {
        var showSettings by remember { mutableStateOf(false) }
        BackHandler(enabled = screen != Screen.HOME) { goBack() }
        MaterialTheme(colorScheme = darkColorScheme(primary = Orange, background = Navy, surface = CardBlue)) {
            Surface(Modifier.fillMaxSize(), color = Navy) {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp)) {
                    Header { showSettings = true }
                    Spacer(Modifier.height(12.dp))
                    if (busy) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Orange)
                                Spacer(Modifier.height(16.dp))
                                Text("Je lis ton cahier…", color = Color.White, fontSize = 20.sp)
                            }
                        }
                    } else when (screen) {
                        Screen.HOME -> Home()
                        Screen.TEXT -> TextScreen()
                        Screen.READER -> Reader()
                        Screen.EXO -> Exo()
                        Screen.QUIZ -> Quiz()
                    }
                }
                if (showSettings) Settings { showSettings = false }
            }
        }
    }

    @Composable
    private fun Header(onSettings: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Orange),
                contentAlignment = Alignment.Center
            ) { Text("AH", color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
            Text("Ali Haïdara", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("⚙️", fontSize = 24.sp) }
        }
    }

    @Composable
    private fun Home() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Bonjour Ali 👋", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Filme ton cahier ou choisis un PDF.\nChoisis la partie, et je te la lis mot par mot ou phrase par phrase.",
                color = Soft, fontSize = 17.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            BigButton("📷  Filmer mon cahier", Orange, Navy) { openCamera() }
            Spacer(Modifier.height(14.dp))
            BigButton("📄  Choisir un PDF", Teal, Navy) { pickPdf.launch(arrayOf("application/pdf")) }
            Spacer(Modifier.height(14.dp))
            BigButton("📝  Exercices", Violet, Navy) { screen = Screen.EXO }
            message?.let {
                Spacer(Modifier.height(20.dp))
                Text(it, color = Color(0xFFFF8A80), fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            if (voiceStatus == 2) {
                Spacer(Modifier.height(20.dp))
                Text("La voix française n'est pas installée sur ce téléphone.", color = Color(0xFFFFE082), textAlign = TextAlign.Center)
                TextButton(onClick = {
                    try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (e: Exception) {
                        message = "Ouvre les réglages du téléphone > Synthèse vocale pour installer le français."
                    }
                }) { Text("Installer la voix française", color = Orange, fontSize = 16.sp) }
            }
        }
    }

    @Composable
    private fun ModeChips(onPick: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "1 mot", 2 to "2 mots", 3 to "3 mots", 0 to "Phrase").forEach { (m, label) ->
                FilterChip(
                    selected = mode == m,
                    onClick = { onPick(m) },
                    label = { Text(label, fontSize = 13.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Orange, selectedLabelColor = Navy)
                )
            }
        }
    }

    @Composable
    private fun TextScreen() {
        Column(Modifier.fillMaxSize()) {
            Text("Choisis la partie à lire", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Appuie longtemps sur le texte pour sélectionner la partie voulue. Sans sélection, je prends tout. Tu peux aussi corriger les fautes.",
                color = Soft, fontSize = 14.sp
            )
            Spacer(Modifier.height(10.dp))
            ModeChips { mode = it }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                textStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
            )
            message?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
            Spacer(Modifier.height(10.dp))
            BigButton("▶  Lire cette partie", Orange, Navy) { startReading() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { openCamera() }) { Text("➕ Photo", color = Soft) }
                TextButton(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) { Text("➕ PDF", color = Soft) }
                TextButton(onClick = { newBook() }) { Text("🆕 Nouveau", color = Soft) }
            }
        }
    }

    @Composable
    private fun Reader() {
        val last = index >= chunks.lastIndex
        val words = mode != 0
        Column(Modifier.fillMaxSize()) {
            ModeChips { mode = it; rechunk() }
            Spacer(Modifier.height(10.dp))
            Text("Morceau ${index + 1} sur ${chunks.size}", color = Soft, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (index + 1f) / chunks.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Orange, trackColor = CardBlue
            )
            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardBlue)
            ) {
                val text = chunks.getOrElse(index) { "" }
                if (words) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(text, color = Color.White, fontSize = 44.sp, lineHeight = 54.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        Text(text, color = Color.White, fontSize = 26.sp, lineHeight = 36.sp)
                        if (last) {
                            Spacer(Modifier.height(20.dp))
                            Text("Bravo ! C'est le dernier morceau 🎉", color = Orange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { if (index > 0) { index--; speakCurrent() } },
                    enabled = index > 0,
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(2.dp, Teal)
                ) { Text("◀", color = Teal, fontSize = 22.sp) }
                Button(
                    onClick = { if (speaking) stopSpeaking() else speakCurrent() },
                    modifier = Modifier.weight(2f).height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange)
                ) { Text(if (speaking) "⏹ Stop" else "🔊 Lire", color = Navy, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { if (!last) { index++; speakCurrent() } },
                    enabled = !last,
                    modifier = Modifier.weight(1.5f).height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                ) { Text("Suivant ▶", color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { startDictee() }) { Text("✍️ Dictée", color = Violet) }
                TextButton(onClick = { stopSpeaking(); screen = Screen.TEXT }) { Text("✏️ Texte", color = Soft) }
                TextButton(onClick = { stopSpeaking(); screen = Screen.EXO }) { Text("📝 Exercices", color = Soft) }
            }
        }
    }

    @Composable
    private fun Exo() {
        var q by remember { mutableStateOf("") }
        var a by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize()) {
            Text("Mes questions et réponses", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Question") },
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = a, onValueChange = { a = it }, label = { Text("Réponse") },
                supportingText = { Text("Plusieurs réponses possibles : sépare avec /") },
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    if (q.isNotBlank() && a.isNotBlank()) {
                        qaList = qaList + QA(q.trim(), a.trim()); saveQa(); q = ""; a = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) { Text("➕ Ajouter", color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (qaList.isEmpty()) Text("Aucune question pour l'instant.", color = Soft)
                qaList.forEachIndexed { i, item ->
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBlue)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.q, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("→ ${item.a}", color = Teal, fontSize = 16.sp)
                            }
                            TextButton(onClick = {
                                qaList = qaList.filterIndexed { j, _ -> j != i }; saveQa()
                            }) { Text("🗑", fontSize = 20.sp) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            BigButton("▶  Lancer le quiz (${qaList.size})", Orange, Navy) { startQuiz(qaList, Screen.EXO) }
            TextButton(onClick = { screen = Screen.HOME }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("← Retour", color = Soft)
            }
        }
    }

    @Composable
    private fun Quiz() {
        if (quizDone) {
            val n = quizItems.size
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(if (quizScore * 2 >= n) "🎉" else "💪", fontSize = 64.sp)
                Text("Tu as trouvé $quizScore sur $n", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                BigButton("🔁  Recommencer", Orange, Navy) { startQuiz(quizItems, quizFrom) }
                Spacer(Modifier.height(12.dp))
                BigButton("← Retour", Teal, Navy) { screen = quizFrom }
            }
            return
        }
        val item = quizItems[quizIndex]
        LaunchedEffect(quizIndex, quizItems) { item.say?.let { say(it) } }
        Column(Modifier.fillMaxSize()) {
            Text("Question ${quizIndex + 1} sur ${quizItems.size}", color = Soft, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardBlue)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item.q, color = Color.White, fontSize = 24.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { say(item.say ?: item.q) }) {
                        Text("🔊 Écouter", color = Orange, fontSize = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = quizAnswer, onValueChange = { quizAnswer = it },
                label = { Text("Ta réponse") },
                enabled = quizResult == null,
                textStyle = TextStyle(fontSize = 22.sp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            when (quizResult) {
                null -> BigButton("✔  Vérifier", Orange, Navy) { checkAnswer() }
                else -> {
                    val ok = quizResult == true
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (ok) "✅ Trouvé ! Bravo" else "❌ Pas trouvé",
                                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            if (!ok) Text("La bonne réponse : ${item.a.substringBefore("/")}", color = Color.White, fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BigButton(if (quizIndex >= quizItems.lastIndex) "🏁  Voir mon score" else "Suivant ▶", Teal, Navy) { nextQuestion() }
                }
            }
        }
    }

    @Composable
    private fun BigButton(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(66.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = bg)
        ) { Text(label, color = fg, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
    }

    @Composable
    private fun Settings(onClose: () -> Unit) {
        var key by remember { mutableStateOf(prefs.getString("groq", "") ?: "") }
        var rate by remember { mutableFloatStateOf(prefs.getFloat("rate", 0.85f)) }
        AlertDialog(
            onDismissRequest = onClose,
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("groq", key.trim()).putFloat("rate", rate).apply()
                    tts?.setSpeechRate(rate)
                    onClose()
                }) { Text("Enregistrer", color = Orange) }
            },
            title = { Text("Réglages") },
            text = {
                Column {
                    Text("Vitesse de la voix")
                    Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..1.3f)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("Clé API Groq (facultatif)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sans clé, tout marche hors ligne. Avec une clé et internet, les fautes de lecture du texte sont corrigées.",
                        fontSize = 13.sp, color = Soft
                    )
                }
            }
        )
    }
}
