package com.orchestrator.utils

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * TokenEstimator: fast, API-free token count estimator.
 *
 * Heuristic:
 * - Base estimate: characters / charsPerToken
 * - Model-specific base ratios and lightweight content-aware adjustments
 * - CJK/multilingual support for accurate non-whitespace-separated language estimation
 * - Goal: within ~10% on average English/code text without external calls
 */
object TokenEstimator {
    /** Model families supported for coarse-grained token ratio tuning. */
    enum class Model {
        DEFAULT,     // Generic LLMs (OpenAI-like)
        OPENAI,      // Explicit alias for DEFAULT
        CLAUDE,      // Anthropic Claude models
        CODEX        // Code-specialized models (e.g., OpenAI Codex)
    }

    /**
     * Base chars-per-token ratios (higher = fewer tokens for same text).
     * These are intentionally simple and empirically reasonable:
     * - DEFAULT/OPENAI ~ 4.0
     * - CLAUDE slightly larger (4.3) → fewer tokens vs DEFAULT on prose
     * - CODEX smaller (3.3) → more tokens for code-heavy text
     *
     * These can be overridden via setModelRatio() for runtime calibration.
     */
    private val baseCharsPerToken: MutableMap<Model, Double> = mutableMapOf(
        Model.DEFAULT to 4.0,
        Model.OPENAI to 4.0,
        Model.CLAUDE to 4.3,
        Model.CODEX to 3.3
    )

    /**
     * Override the base chars-per-token ratio for a specific model.
     * Useful for runtime calibration, experimentation, or configuration-driven adjustments.
     */
    fun setModelRatio(model: Model, charsPerToken: Double) {
        require(charsPerToken > 0.0) { "charsPerToken must be > 0" }
        baseCharsPerToken[model] = charsPerToken
    }

    /**
     * Get the current base chars-per-token ratio for a model.
     */
    fun getModelRatio(model: Model): Double {
        return baseCharsPerToken[model] ?: baseCharsPerToken[Model.DEFAULT]!!
    }

    /** Estimate tokens for given text and model family. */
    fun estimateTokens(text: CharSequence?, model: Model = Model.DEFAULT): Int {
        if (text.isNullOrEmpty()) return 0
        val normalized = normalize(text)
        val baseRatio = baseCharsPerToken[model] ?: baseCharsPerToken[Model.DEFAULT]!!
        val adjustedRatio = applyContentAdjustments(normalized, baseRatio, model)
        val estimate = ceil(normalized.length / adjustedRatio).toInt()
        return max(0, estimate)
    }

    /** Estimate tokens using a custom chars-per-token ratio. */
    fun estimateTokens(text: CharSequence?, charsPerToken: Double): Int {
        require(charsPerToken > 0.0) { "charsPerToken must be > 0" }
        if (text.isNullOrEmpty()) return 0
        val normalized = normalize(text)
        val estimate = ceil(normalized.length / charsPerToken).toInt()
        return max(0, estimate)
    }

    /** Basic normalization to stabilize character counts without losing intentional whitespace. */
    private fun normalize(text: CharSequence): String {
        // Collapse CRLF to LF, keep other chars intact.
        // IMPORTANT: Do NOT trim() - budget enforcement needs exact token counts.
        return text.toString()
            .replace("\r\n", "\n")
            .replace("\r", "\n")
    }

    /** Punctuation character set (hoisted to avoid allocations). */
    private val PUNCTUATION_CHARS = setOf('.', ',', ';', ':', '!', '?', '\'', '"')

    /**
     * Lightweight content-aware adjustments to chars-per-token ratio.
     * - CJK detection: adjust for non-whitespace-separated languages
     * - CODEX: detect code density → reduce charsPerToken (i.e., increase tokens)
     * - CLAUDE: punctuation/newline heavy text → slightly reduce charsPerToken
     *
     * Adjustments are intentionally small and fast to compute.
     */
    private fun applyContentAdjustments(text: String, baseCPT: Double, model: Model): Double {
        var cpt = baseCPT

        val length = text.length
        if (length == 0) return cpt

        // Compute quick stats
        var letters = 0
        var cjkChars = 0
        var newlines = 0
        var punctuation = 0
        var symbols = 0
        var whitespace = 0

        for (ch in text) {
            when {
                ch.isLetter() -> {
                    letters++
                    // Detect CJK characters (major Unicode blocks)
                    if (isCJK(ch)) cjkChars++
                }
                ch == ' ' || ch == '\t' -> whitespace++
                ch == '\n' -> newlines++
                ch in PUNCTUATION_CHARS -> punctuation++
                // treat the rest of visible ASCII as symbols (operators, braces, etc.)
                ch.code in 33..126 -> symbols++
            }
        }

        val cjkDensity = if (length > 0) cjkChars.toDouble() / length else 0.0
        val symbolDensity = if (length > 0) symbols.toDouble() / length else 0.0
        val punctuationDensity = if (length > 0) punctuation.toDouble() / length else 0.0
        val newlineDensity = if (length > 0) newlines.toDouble() / length else 0.0
        val whitespaceDensity = if (length > 0) whitespace.toDouble() / length else 0.0
        val nonLetters = length - letters
        val nonLetterShare = if (length > 0) nonLetters.toDouble() / length else 0.0

        // CJK adjustment (applies to all models)
        if (cjkDensity > 0.3) {
            // High CJK content: tokens are approximately 1 character each
            // Adjust ratio down to ~1.7 chars/token to avoid massive undercounts
            cpt = min(cpt, 1.7)
        } else if (cjkDensity > 0.1) {
            // Mixed CJK/English: interpolate between normal and CJK ratio
            val cjkFactor = (cjkDensity - 0.1) / 0.2  // 0.1-0.3 maps to 0.0-1.0
            val targetCPT = 1.7 + (baseCPT - 1.7) * (1.0 - cjkFactor)
            cpt = targetCPT
        }

        // Model-specific adjustments
        when (model) {
            Model.CODEX -> {
                // Code often has many symbols/braces/operators and fewer long words.
                // If symbol density is high, reduce chars-per-token up to ~10%.
                val factor = 1.0 - min(0.10, symbolDensity * 0.5 + nonLetterShare * 0.05)
                cpt *= factor
            }
            Model.CLAUDE -> {
                // Slightly sensitive to punctuation/newlines; lower cpt modestly up to ~5%.
                val factor = 1.0 - min(0.05, punctuationDensity * 0.5 + newlineDensity * 0.5)
                cpt *= factor
            }
            else -> {
                // DEFAULT/OPENAI: minor adjustment if extreme punctuation/newlines
                // Also account for whitespace density (sparse code)
                val factor = 1.0 - min(0.03, punctuationDensity * 0.3 + newlineDensity * 0.3 + whitespaceDensity * 0.05)
                cpt *= factor
            }
        }

        // Keep cpt within sane bounds to avoid pathological outputs.
        cpt = cpt.coerceIn(1.5, 8.0)
        return cpt
    }

    /**
     * Check if character is CJK (Chinese, Japanese, Korean).
     * Covers major Unicode blocks for CJK ideographs and Hangul.
     */
    private fun isCJK(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||  // CJK Unified Ideographs
               code in 0x3400..0x4DBF ||  // CJK Extension A
               code in 0x20000..0x2A6DF || // CJK Extension B
               code in 0xAC00..0xD7AF ||  // Hangul Syllables
               code in 0x3040..0x309F ||  // Hiragana
               code in 0x30A0..0x30FF     // Katakana
    }
}