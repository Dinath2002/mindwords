package com.mindword

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindword.game.Round
import com.mindword.game.RoundOutcome
import com.mindword.net.DreamloClient
import com.mindword.net.ScoreRow
import com.mindword.net.WordsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- helper: keeps Dreamlo name safe ---
private fun sanitizeName(input: String): String =
    input.trim()
        .replace(Regex("""\s+"""), " ")           // collapse whitespace
        .replace(Regex("""[^\p{ASCII}]"""), "")   // strip non-ASCII
        .take(20)
        .ifBlank { "Player" }

@Composable
fun PlayScreen(player: String, onOpenBoard: () -> Unit) {
    val scope = rememberCoroutineScope()

    var sessionStart by remember { mutableStateOf(System.currentTimeMillis()) }
    var total by remember { mutableStateOf(0) }
    var solved by remember { mutableStateOf(0) }
    var level by remember { mutableStateOf(1) }   // ✅ track player level

    var round by remember { mutableStateOf<Round?>(null) }
    var status by remember { mutableStateOf("Loading word…") }
    var guess by remember { mutableStateOf("") }
    var letter by remember { mutableStateOf("") }
    var inputsEnabled by remember { mutableStateOf(false) }

    // ✅ word length range per level
    fun levelRange(lvl: Int): IntRange = when {
        lvl <= 1 -> 4..6
        lvl == 2 -> 5..7
        lvl == 3 -> 6..8
        else -> 7..9
    }

    fun loadNewWord() = scope.launch {
        inputsEnabled = false
        status = "Loading word…"
        val r = levelRange(level)
        val w = withContext(Dispatchers.IO) { WordsApi.fetchOne(r.first, r.last) }
        if (w == null) {
            status = "Failed to fetch word. Tap NEW."
            return@launch
        }
        round = Round(w)
        sessionStart = System.currentTimeMillis()
        status = ""
        inputsEnabled = true
    }

    fun autoFailIfZero(r: Round) {
        if (r.score <= 0) {
            status = "Score reached 0. New word…"
            guess = ""; letter = ""
            loadNewWord()
        }
    }

    LaunchedEffect(Unit) { loadNewWord() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0A1F))
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Hi, $player (Lv.$level)", color = Color(0xFFA8A4C9))   // ✅ show level
            Text("Total: $total", color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold)
        }
        Text("Solved: $solved", color = Color(0xFFA8A4C9), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161432))) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(round?.mask() ?: "••••", fontSize = 40.sp, color = Color(0xFFE9E7F7))
                Spacer(Modifier.height(8.dp))
                Text(status, color = Color(0xFFA8A4C9))
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = guess, onValueChange = { guess = it },
            label = { Text("Full guess") },
            singleLine = true, enabled = inputsEnabled, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = letter, onValueChange = { letter = it.take(1) },
            label = { Text("Letter (a-z)") },
            singleLine = true, enabled = inputsEnabled, modifier = Modifier.fillMaxWidth(0.5f)
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val r = round ?: return@Button
                    if (guess.isBlank()) { status = "Type your guess"; return@Button }
                    when (val out = r.guess(guess.trim())) {
                        is RoundOutcome.Win -> {
                            total += r.score
                            solved += 1
                            level += 1      // ✅ level up
                            status = "Correct! +${r.score}"
                            guess = ""; letter = ""
                            loadNewWord()
                        }
                        is RoundOutcome.OutOfTries -> {
                            status = "Out of tries. It was ${out.answer.uppercase()}"; loadNewWord()
                        }
                        RoundOutcome.Continue -> {
                            status = "Nope. Keep trying…"; guess = ""
                            autoFailIfZero(r)
                        }
                    }
                },
                enabled = inputsEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                modifier = Modifier.weight(1f)
            ) { Text("Guess", color = Color.White) }

            OutlinedButton(
                onClick = {
                    val r = round ?: return@OutlinedButton
                    if (letter.isBlank()) { status = "Type a letter"; return@OutlinedButton }
                    status = r.letterCount(letter[0]); letter = ""
                    autoFailIfZero(r)
                },
                enabled = inputsEnabled,
                modifier = Modifier.weight(1f)
            ) { Text("Count (-5)") }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val r = round ?: return@OutlinedButton
                    status = r.lengthClue()
                    autoFailIfZero(r)
                },
                enabled = inputsEnabled,
                modifier = Modifier.weight(1f)
            ) { Text("Length (-5)") }

            OutlinedButton(
                onClick = {
                    val r = round ?: return@OutlinedButton
                    status = r.hintLetter()
                    autoFailIfZero(r)
                },
                enabled = inputsEnabled,
                modifier = Modifier.weight(1f)
            ) { Text("Hint (-5)") }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { loadNewWord() }) { Text("New") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val seconds = ((System.currentTimeMillis() - sessionStart) / 1000).toInt()
                    val clean = sanitizeName(player)
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { DreamloClient.addPipe(clean, total, seconds) }
                        val err = DreamloClient.lastError?.let { " (${it.take(60)})" } ?: ""
                        status = if (ok) "Leaderboard updated" else "Leaderboard update failed$err"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) { Text("Stop & Upload", color = Color.White) }

            OutlinedButton(onClick = onOpenBoard) { Text("Board") }
        }
    }
}

@Composable
fun BoardScreen(onBack: () -> Unit) {
    var rows by remember { mutableStateOf(listOf<ScoreRow>()) }
    var state by remember { mutableStateOf("Loading…") }

    LaunchedEffect(Unit) {
        state = "Loading…"
        rows = withContext(kotlinx.coroutines.Dispatchers.IO) { DreamloClient.topJson(30) }
        state = if (rows.isEmpty()) "No scores yet" else ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0A1F))
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Leaderboard", color = Color(0xFFE9E7F7), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(12.dp))
        if (state.isNotEmpty()) {
            Text(state, color = Color(0xFFA8A4C9))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(rows) { i, r -> LeaderRow(index = i + 1, row = r) }
            }
        }
    }
}

@Composable
private fun LeaderRow(index: Int, row: ScoreRow) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161432))) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$index. ${row.name}", color = Color(0xFFE9E7F7))
            Text("${row.score} pts • ${row.seconds}s", color = Color(0xFFA8A4C9))
        }
    }
}
