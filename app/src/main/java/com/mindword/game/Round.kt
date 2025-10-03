package com.mindword.game

import kotlin.math.max
import kotlin.random.Random

sealed interface RoundOutcome {
    data object Continue : RoundOutcome
    data object Win : RoundOutcome
    data class OutOfTries(val answer: String) : RoundOutcome
}

class Round(
    private val answer: String,
    private val maxTries: Int = 10
) {
    var score = 100; private set
    var tries = 0; private set

    private val revealed = mutableSetOf<Int>()
    private var hintUsed = false
    private val rng = Random(System.currentTimeMillis())

    fun mask(): String = buildString {
        answer.forEachIndexed { i, c -> append(if (i in revealed) c else '•') }
    }

    /** Full-word guess: wrong = tries++ and −10; stop at maxTries */
    fun guess(word: String): RoundOutcome {
        if (word.equals(answer, ignoreCase = true)) {
            answer.indices.forEach { revealed.add(it) }
            return RoundOutcome.Win
        }
        tries += 1
        score = max(0, score - 10)
        return if (tries >= maxTries) RoundOutcome.OutOfTries(answer) else RoundOutcome.Continue
    }

    /** Letter occurrence clue: reveal matches and cost −5 (does NOT consume a try) */
    fun letterCount(ch: Char): String {
        val c = ch.lowercaseChar()
        var n = 0
        answer.forEachIndexed { i, a ->
            if (a.lowercaseChar() == c) { n++; revealed.add(i) }
        }
        score = max(0, score - 5)
        val display = c.uppercaseChar()
        return if (n > 0) {
            "There ${if (n == 1) "is" else "are"} $n '$display'."
        } else {
            "No '$display' here."
        }
    }

    /** Length clue: cost −5 (does NOT consume a try) */
    fun lengthClue(): String {
        score = max(0, score - 5)
        return "It has ${answer.length} letters."
    }

    /** Single hint after 5 guesses: reveal one letter and cost −5 */
    fun hintLetter(): String {
        if (tries < 5) return "Hints unlock after 5 guesses."
        if (hintUsed) return "Hint already used."
        val slots = answer.indices.filterNot { it in revealed }
        if (slots.isEmpty()) return "Everything’s already revealed."
        val i = slots.random(rng)
        revealed.add(i)
        hintUsed = true
        score = max(0, score - 5)
        return "Revealed: position ${i + 1} is '${answer[i].uppercaseChar()}'."
    }

    /** Lowercased answer so UI can fetch rhymes/similar words */
    fun answerForTip(): String = answer.lowercase()
}
