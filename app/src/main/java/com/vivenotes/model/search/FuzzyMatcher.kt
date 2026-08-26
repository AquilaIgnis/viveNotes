package com.vivenotes.model.search

import kotlin.math.max
import kotlin.math.min

/**
 * A stretch of the searched text that the query is responsible for — what the panel emphasises in a
 * snippet, and what the editor selects when a result is opened.
 */
data class MatchSpan(val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/** How well a query fits one piece of text, and where it landed. [spans] is sorted and disjoint. */
data class TextMatch(val score: Int, val spans: List<MatchSpan>) {
    val start: Int get() = spans.first().start
    val end: Int get() = spans.last().end
}

/**
 * Typo-tolerant matching of a query against one block of text — `memory/searchPlan.md` CS6.
 *
 * **Every term must match.** A query is split on whitespace and each term scored independently, so
 * adding a word narrows the search rather than widening it. That is the behaviour anyone expects from
 * a search box, and it is the opposite of what an OR would do.
 *
 * Each term takes the best of three, in descending order of confidence: a substring, a word whose
 * *prefix* is within a typo budget of the term, or a subsequence inside a single word. The one rule
 * worth stating is the one that is absent — **free subsequence across the whole block is not used.**
 * It is the classic fuzzy-finder rule, and on prose it matches almost everything: `abc` would find
 * any line containing an a, later a b and later a c, which is most lines. Bounding it to one word
 * keeps `cntr` → `container` and drops the noise.
 *
 * Pure Kotlin with no Android types, so this is testable on the JVM — which, with the emulator broken
 * (R10), is the difference between a tested matcher and an unverified one.
 */
object FuzzyMatcher {

    /**
     * Scores [query] against [text], or returns null when some term does not appear at all.
     *
     * [text] is matched case-insensitively; the spans returned index into [text] as given, so a
     * caller can highlight or select against the original.
     */
    fun match(query: String, text: String): TextMatch? {
        val terms = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (terms.isEmpty() || text.isEmpty()) return null

        val haystack = text.lowercase()
        val words = wordsIn(haystack)

        var total = 0
        val spans = mutableListOf<MatchSpan>()
        for (term in terms) {
            val hit = matchTerm(term, haystack, words) ?: return null
            total += hit.score
            spans += hit.spans
        }

        // A match at the head of the line is more likely to be the one the reader meant, but only
        // just — this is a tie-breaker between otherwise equal blocks, not a ranking of its own.
        val earliest = spans.minOf { it.start }
        total += max(0, HEAD_BONUS - earliest / HEAD_FALLOFF)

        return TextMatch(score = total, spans = merged(spans))
    }

    private fun matchTerm(term: String, haystack: String, words: List<MatchSpan>): TextMatch? {
        substring(term, haystack, words)?.let { return it }
        typo(term, haystack, words)?.let { return it }
        return subsequenceInWord(term, haystack, words)
    }

    /** The common case: the term is simply there. Worth more where it starts or fills a word. */
    private fun substring(term: String, haystack: String, words: List<MatchSpan>): TextMatch? {
        val at = haystack.indexOf(term)
        if (at < 0) return null
        // Prefer a later occurrence that starts a word over an earlier one buried mid-word: "cat"
        // in "concatenate cat" is the second one.
        var best = at
        var bestScore = scoreSubstring(term, best, words)
        var next = haystack.indexOf(term, best + 1)
        while (next >= 0) {
            val score = scoreSubstring(term, next, words)
            if (score > bestScore) {
                best = next
                bestScore = score
            }
            next = haystack.indexOf(term, next + 1)
        }
        return TextMatch(bestScore, listOf(MatchSpan(best, best + term.length)))
    }

    private fun scoreSubstring(term: String, at: Int, words: List<MatchSpan>): Int {
        val word = words.firstOrNull { at >= it.start && at < it.end }
        val startsWord = word != null && word.start == at
        val wholeWord = word != null && word.start == at && word.length == term.length
        return SUBSTRING + (if (startsWord) WORD_START_BONUS else 0) + (if (wholeWord) WHOLE_WORD_BONUS else 0)
    }

    /**
     * A word whose opening is within the typo budget of the term.
     *
     * **Prefix distance, not whole-word distance**: the term is allowed to stop anywhere inside the
     * word, which is what lets `contn` reach `container` — five edits away as whole words, one away
     * from its first five characters. Without it, typo tolerance would only ever work on words the
     * user happened to type in full.
     */
    private fun typo(term: String, haystack: String, words: List<MatchSpan>): TextMatch? {
        val budget = typoBudget(term.length)
        if (budget == 0) return null
        var bestWord: MatchSpan? = null
        var bestDistance = Int.MAX_VALUE
        for (word in words) {
            val distance = prefixDistance(term, haystack, word, budget) ?: continue
            if (distance < bestDistance) {
                bestWord = word
                bestDistance = distance
                if (distance == 0) break
            }
        }
        val word = bestWord ?: return null
        // The span covers as much of the word as the term could account for, so a typo'd term still
        // highlights — and selects — something the reader recognises.
        val covered = min(word.length, term.length + bestDistance)
        return TextMatch(
            score = TYPO - TYPO_PENALTY * bestDistance,
            spans = listOf(MatchSpan(word.start, word.start + covered)),
        )
    }

    /**
     * How many edits it takes to turn [term] into some prefix of the word at [word], or null past
     * [budget].
     *
     * Bounded Levenshtein over two rows, abandoned as soon as a whole row is out of budget — which is
     * what keeps this affordable against every word of a notebook.
     */
    private fun prefixDistance(term: String, haystack: String, word: MatchSpan, budget: Int): Int? {
        val rows = term.length
        val columns = word.length
        var previous = IntArray(columns + 1) { it }
        var current = IntArray(columns + 1)
        for (row in 1..rows) {
            current[0] = row
            var rowBest = current[0]
            for (column in 1..columns) {
                val substitution = if (term[row - 1] == haystack[word.start + column - 1]) 0 else 1
                current[column] = min(
                    min(previous[column] + 1, current[column - 1] + 1),
                    previous[column - 1] + substitution,
                )
                rowBest = min(rowBest, current[column])
            }
            if (rowBest > budget) return null
            val swap = previous
            previous = current
            current = swap
        }
        // Every column of the last row is the distance to one prefix; the shortest wins. Column 0 is
        // excluded: matching the empty prefix is matching nothing.
        val best = (1..columns).minOf { previous[it] }
        return best.takeIf { it <= budget }
    }

    /**
     * The term's letters in order inside one word — `cntr` → `container`.
     *
     * The last resort, and scored like one. Penalised by how far it had to spread, so a term found in
     * eight characters beats the same term strung across twenty.
     */
    private fun subsequenceInWord(term: String, haystack: String, words: List<MatchSpan>): TextMatch? {
        for (word in words) {
            if (word.length < term.length) continue
            var at = word.start
            var index = 0
            var first = -1
            while (at < word.end && index < term.length) {
                if (haystack[at] == term[index]) {
                    if (first < 0) first = at
                    index++
                }
                at++
            }
            if (index == term.length) {
                val spread = at - first - term.length
                return TextMatch(
                    score = max(SUBSEQUENCE_FLOOR, SUBSEQUENCE - spread * SPREAD_PENALTY),
                    spans = listOf(MatchSpan(first, at)),
                )
            }
        }
        return null
    }

    /**
     * How many typos a term of this length may carry.
     *
     * Zero below four characters: one edit on a three-letter term reaches a large share of the
     * dictionary, so a budget there would return the notebook rather than an answer.
     */
    internal fun typoBudget(length: Int): Int = when {
        length < 4 -> 0
        length < 7 -> 1
        else -> 2
    }

    /** Word ranges, where a word is a run of letters or digits. */
    private fun wordsIn(text: String): List<MatchSpan> {
        val words = mutableListOf<MatchSpan>()
        var start = -1
        text.forEachIndexed { index, character ->
            if (character.isLetterOrDigit()) {
                if (start < 0) start = index
            } else if (start >= 0) {
                words += MatchSpan(start, index)
                start = -1
            }
        }
        if (start >= 0) words += MatchSpan(start, text.length)
        return words
    }

    /** Sorted, with overlapping and touching spans folded together so a highlight has no seams. */
    private fun merged(spans: List<MatchSpan>): List<MatchSpan> {
        val sorted = spans.sortedBy { it.start }
        val out = mutableListOf<MatchSpan>()
        sorted.forEach { span ->
            val last = out.lastOrNull()
            if (last != null && span.start <= last.end) {
                out[out.lastIndex] = MatchSpan(last.start, max(last.end, span.end))
            } else {
                out += span
            }
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")

    // The three tiers of CS6, far enough apart that no combination of bonuses lets a lower tier
    // outrank a higher one.
    private const val SUBSTRING = 100
    private const val TYPO = 60
    private const val SUBSEQUENCE = 30

    private const val WORD_START_BONUS = 25
    private const val WHOLE_WORD_BONUS = 10
    private const val TYPO_PENALTY = 12
    private const val SPREAD_PENALTY = 2
    private const val SUBSEQUENCE_FLOOR = 10
    private const val HEAD_BONUS = 10
    private const val HEAD_FALLOFF = 8
}
