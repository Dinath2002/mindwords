package com.mindword

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mindword.game.Round
import com.mindword.game.RoundOutcome
import com.mindword.net.DreamloClient
import com.mindword.net.ScoreRow
import com.mindword.net.WordsApi
import com.mindword.ui.theme.LexiSnapTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LexiSnapApp() }
    }
}

@Composable
fun LexiSnapApp() {
    val nav = rememberNavController()

    LexiSnapTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            NavHost(nav, startDestination = "welcome") {
                composable("welcome") { WelcomeScreen(nav) }
                composable("play/{player}") { backStack ->
                    val player = backStack.arguments?.getString("player") ?: "Player"
                    PlayScreenV2(player = player, onOpenBoard = { nav.navigate("board") })
                }
                composable("board") { BoardScreenV2(onBack = { nav.popBackStack() }) }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(nav: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf(Prefs.loadName(ctx) ?: "") }
    val canStart = name.trim().isNotBlank()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.ExtraBold
                            )
                        ) { append("MIND") }
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        ) { append("WORDS") }
                    },
                    fontSize = 34.sp, letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Guess the word. 4–6 letters at first. One tip unlocks after five attempts.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("Your name") },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Max 20 characters",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp)
                        .alpha(0.9f)
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val final = name.trim().ifBlank { "Player" }
                        Prefs.saveName(ctx, final)
                        nav.navigate("play/$final")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) { Text(if (canStart) "Start" else "Start as Guest") }
            }
        }
    }
}

/* ------------ GAME SCREENS ------------ */

private fun sanitizeName(input: String): String =
    input.trim()
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""[^\p{ASCII}]"""), "")
        .take(20)
        .ifBlank { "Player" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreenV2(player: String, onOpenBoard: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // persisted level
    var level by remember { mutableStateOf(Prefs.loadLevel(ctx)) }

    // session & timer
    var sessionStart by remember { mutableStateOf(System.currentTimeMillis()) }
    var elapsed by remember { mutableStateOf(0) }

    // scoreboard
    var total by remember { mutableStateOf(0) }
    var solved by remember { mutableStateOf(0) }

    // round state
    var round by remember { mutableStateOf<Round?>(null) }
    var status by remember { mutableStateOf("Loading word…") }
    var guess by remember { mutableStateOf("") }
    var letter by remember { mutableStateOf("") }
    var inputsEnabled by remember { mutableStateOf(false) }

    // UI helpers
    var showRules by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }

    fun levelRange(lvl: Int): IntRange = when {
        lvl <= 1 -> 4..6
        lvl == 2 -> 5..7
        lvl == 3 -> 6..8
        else -> 7..9
    }

    fun loadNewWord() = scope.launch {
        isLoading = true
        loadError = false
        inputsEnabled = false
        status = "Loading word…"
        val r = levelRange(level)
        val w = withContext(Dispatchers.IO) { WordsApi.fetchOne(r.first, r.last) }
        if (w == null) {
            status = "Failed to fetch word. Tap Retry."
            isLoading = false
            loadError = true
            return@launch
        }
        round = Round(w) // resets score=100, tries=0
        sessionStart = System.currentTimeMillis()
        elapsed = 0
        status = ""
        inputsEnabled = true
        guess = ""
        letter = ""
        isLoading = false
        loadError = false
    }

    fun autoFailIfZero(r: Round) {
        if (r.score <= 0) {
            status = "Score reached 0. New word…"
            loadNewWord()
        }
    }

    // initial & on level change
    LaunchedEffect(level) { loadNewWord() }

    // live timer
    LaunchedEffect(sessionStart, inputsEnabled) {
        while (inputsEnabled) {
            delay(1000)
            elapsed = ((System.currentTimeMillis() - sessionStart) / 1000).toInt()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Play", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showRules = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Scoring rules")
                    }
                    TextButton(onClick = { loadNewWord() }) { Text("New") }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->

        // Loading / Error states
        when {
            isLoading -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(inner),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            loadError -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Couldn't fetch a word. Check your connection.",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { loadNewWord() }) { Text("Retry") }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header & stats
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Hi, $player",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Solved: $solved",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatPill(label = "Level", value = "$level")
                            StatPill(
                                label = "Total",
                                value = "$total",
                                valueColor = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    val tries = round?.tries ?: 0
                    Text(
                        text = "Attempt ${tries.coerceAtLeast(0)} of 10",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Scoring: start 100 • -10 wrong guess • -5 clues (letter/length/hint) • hint after 5 guesses",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )

                    // Word card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = round?.mask() ?: "••••",
                                fontSize = 40.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                status,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Score + Timer row
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Score: ${round?.score ?: 100}",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                        Text(
                            "Time: ${elapsed}s",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }

                    // Inputs row (letter + big Guess)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = letter,
                            onValueChange = { letter = it.take(1) },
                            label = { Text("Letter (a–z)") },
                            singleLine = true,
                            enabled = inputsEnabled,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val r = round ?: return@Button
                                val targetLen = r.mask().length
                                val cleaned = guess.trim()
                                if (cleaned.isBlank()) { status = "Type your guess"; return@Button }
                                if (!cleaned.all { it.isLetter() }) { status = "Use letters only (A–Z)."; return@Button }
                                if (cleaned.length != targetLen) {
                                    status = "Your guess must be $targetLen letters."
                                    return@Button
                                }
                                when (val out = r.guess(cleaned)) {
                                    is RoundOutcome.Win -> {
                                        total += r.score
                                        solved += 1
                                        level += 1
                                        Prefs.saveLevel(ctx, level)
                                        status = "Correct! +${r.score}"
                                        loadNewWord()
                                    }
                                    is RoundOutcome.OutOfTries -> {
                                        status = "Out of tries. It was ${out.answer.uppercase()}"
                                        loadNewWord()
                                    }
                                    RoundOutcome.Continue -> {
                                        status = "Nope. Keep trying…"
                                        guess = ""
                                        autoFailIfZero(r)
                                    }
                                }
                            },
                            enabled = inputsEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) { Text("Guess") }
                    }

                    // Full guess field
                    OutlinedTextField(
                        value = guess,
                        onValueChange = { guess = it },
                        label = { Text("Full guess") },
                        singleLine = true,
                        enabled = inputsEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Secondary actions
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val r = round ?: return@OutlinedButton
                                status = r.lengthClue()
                                autoFailIfZero(r)
                            },
                            enabled = inputsEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) { Text("Length (-5)") }

                        OutlinedButton(
                            onClick = {
                                val r = round ?: return@OutlinedButton
                                if (letter.isBlank()) { status = "Type a letter"; return@OutlinedButton }
                                status = r.letterCount(letter[0])
                                letter = ""
                                autoFailIfZero(r)
                            },
                            enabled = inputsEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) { Text("Count (-5)") }

                        OutlinedButton(
                            onClick = {
                                val r = round ?: return@OutlinedButton
                                if (r.tries < 5) { status = "Tips unlock after 5 guesses."; return@OutlinedButton }
                                val tipWord = r.answerForTip()
                                val tip = WordsApi.tipFor(tipWord)
                                val gateMsg = r.hintLetter() // charges −5 and gates to once
                                status = if (gateMsg.startsWith("Hint already used") || gateMsg.startsWith("Everything")) gateMsg else tip
                                autoFailIfZero(r)
                            },
                            enabled = inputsEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) { Text("Tip (-5)") }
                    }

                    Spacer(Modifier.weight(1f))

                    // Bottom actions
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val seconds = ((System.currentTimeMillis() - sessionStart) / 1000).toInt()
                                val clean = sanitizeName(player)
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        DreamloClient.addPipe(clean, total, seconds)
                                    }
                                    val err = DreamloClient.lastError?.let { " (${it.take(60)})" } ?: ""
                                    status = if (ok) "Leaderboard updated" else "Leaderboard update failed$err"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) { Text("Stop & Upload") }

                        OutlinedButton(
                            onClick = onOpenBoard,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) { Text("Board") }
                    }
                }
            }
        }
    }

    // RULES DIALOG
    if (showRules) {
        AlertDialog(
            onDismissRequest = { showRules = false },
            confirmButton = { TextButton(onClick = { showRules = false }) { Text("OK") } },
            title = { Text("Scoring Rules") },
            text = {
                Text(
                    "• Start at 100 points\n" +
                            "• −10 per wrong full-word guess\n" +
                            "• −5 per clue (letter count, length, or hint)\n" +
                            "• Hint available after 5 guesses, once per round\n" +
                            "• Max 10 guesses per round"
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreenV2(onBack: () -> Unit) {
    var rows by remember { mutableStateOf(listOf<ScoreRow>()) }
    var state by remember { mutableStateOf("Loading…") }

    LaunchedEffect(Unit) {
        state = "Loading…"
        rows = withContext(Dispatchers.IO) { DreamloClient.topJson(30) }
        state = if (rows.isEmpty()) "No scores yet" else ""
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Leaderboard", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner)
                .padding(16.dp)
        ) {
            if (state.isNotEmpty()) {
                Text(state, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(rows) { i, r -> LeaderRow(index = i + 1, row = r) }
                }
            }
        }
    }
}

@Composable
private fun LeaderRow(index: Int, row: ScoreRow) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$index. ${row.name}", color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${row.score} pts • ${row.seconds}s",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}

/** Small “pill” used for Level and Total stats */
@Composable
private fun StatPill(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Text(value, fontWeight = FontWeight.SemiBold, color = valueColor)
        }
    }
}
