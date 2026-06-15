package com.orchestrator.context.embedding

import java.text.Normalizer
import com.orchestrator.utils.Logger

/**
 * BERT-compatible WordPiece tokenizer for sentence-transformer models
 * (all-MiniLM-L6-v2 uses the bert-base-uncased vocabulary).
 *
 * The vocabulary is loaded from the bundled `/models/vocab.txt` resource (30522
 * tokens, one per line, line index == token id). This must match the vocabulary
 * the ONNX model was trained with — a mismatched or stub vocabulary produces
 * out-of-distribution token ids and destroys semantic search quality.
 */
class BertTokenizer(
    private val vocabulary: Map<String, Int> = defaultVocabulary,
    private val maxSequenceLength: Int = 512
) {
    companion object {
        private val log = Logger.logger("com.orchestrator.context.embedding.BertTokenizer")

        // Standard BERT special tokens
        const val CLS_TOKEN = "[CLS]"
        const val SEP_TOKEN = "[SEP]"
        const val UNK_TOKEN = "[UNK]"
        const val PAD_TOKEN = "[PAD]"
        const val MASK_TOKEN = "[MASK]"

        // Special token IDs (standard BERT)
        const val CLS_ID = 101
        const val SEP_ID = 102
        const val UNK_ID = 100
        const val PAD_ID = 0
        const val MASK_ID = 103

        // WordPiece never splits a single "word" longer than this; it becomes [UNK].
        private const val MAX_INPUT_CHARS_PER_WORD = 100

        private const val VOCAB_RESOURCE = "/models/vocab.txt"

        /**
         * The real bert-base-uncased vocabulary, loaded once from the bundled
         * resource. Falls back to a minimal stub only if the resource is missing
         * (so unit tests without the file still construct), logging loudly.
         */
        val defaultVocabulary: Map<String, Int> by lazy { loadVocabularyFromResource() }

        private fun loadVocabularyFromResource(): Map<String, Int> {
            val stream = BertTokenizer::class.java.getResourceAsStream(VOCAB_RESOURCE)
            if (stream == null) {
                log.error(
                    "Vocabulary resource $VOCAB_RESOURCE not found; falling back to stub vocabulary. " +
                        "Semantic embeddings will be degraded — bundle the model's vocab.txt."
                )
                return stubVocabulary
            }
            val vocab = HashMap<String, Int>(32_768)
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    // vocab.txt has exactly one token per line; the line index is the id.
                    val token = line.trim('\n', '\r')
                    if (token.isNotEmpty()) vocab[token] = index
                }
            }
            log.info("Loaded BERT vocabulary: ${vocab.size} tokens from $VOCAB_RESOURCE")
            return vocab
        }

        /** Minimal fallback used only when the vocab resource is unavailable. */
        private val stubVocabulary: Map<String, Int> = mapOf(
            PAD_TOKEN to PAD_ID,
            UNK_TOKEN to UNK_ID,
            CLS_TOKEN to CLS_ID,
            SEP_TOKEN to SEP_ID,
            MASK_TOKEN to MASK_ID
        )
    }

    /**
     * Tokenize text into BERT token IDs: [CLS] + wordpiece tokens + [SEP],
     * truncated to [maxSequenceLength].
     */
    fun tokenize(text: String): IntArray {
        val tokens = ArrayList<Int>(minOf(text.length, maxSequenceLength) + 2)
        tokens.add(CLS_ID)

        val basicTokens = basicTokenize(text)
        val wordpieceTokens = wordpieceTokenize(basicTokens)

        for (token in wordpieceTokens) {
            if (tokens.size >= maxSequenceLength - 1) break // reserve space for [SEP]
            tokens.add(token)
        }

        tokens.add(SEP_ID)
        return tokens.toIntArray()
    }

    /**
     * Basic tokenization mirroring bert-base-uncased: lowercase, strip accents,
     * split on whitespace and punctuation, isolate CJK characters.
     */
    private fun basicTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.setLength(0)
            }
        }

        val cleaned = stripAccents(text.lowercase())
        for (char in cleaned) {
            when {
                isControl(char) -> { /* drop */ }
                char.isWhitespace() -> flush()
                isPunctuation(char) -> {
                    flush()
                    tokens.add(char.toString())
                }
                isCjk(char) -> {
                    flush()
                    tokens.add(char.toString())
                }
                else -> current.append(char)
            }
        }
        flush()
        return tokens
    }

    /** Lowercase already applied; remove combining marks (accents) via NFD. */
    private fun stripAccents(text: String): String {
        if (text.all { it.code < 0x80 }) return text // fast path: pure ASCII
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(normalized.length)
        for (char in normalized) {
            if (Character.getType(char) != Character.NON_SPACING_MARK.toInt()) {
                sb.append(char)
            }
        }
        return sb.toString()
    }

    /**
     * Greedy longest-match-first WordPiece tokenization.
     */
    private fun wordpieceTokenize(tokens: List<String>): List<Int> {
        val output = mutableListOf<Int>()

        for (token in tokens) {
            if (token.length > MAX_INPUT_CHARS_PER_WORD) {
                output.add(UNK_ID)
                continue
            }

            var isBad = false
            var start = 0
            val subTokenIds = mutableListOf<Int>()

            while (start < token.length) {
                var end = token.length
                var matchedId: Int? = null
                while (start < end) {
                    val substr = if (start > 0) "##${token.substring(start, end)}" else token.substring(start, end)
                    val id = vocabulary[substr]
                    if (id != null) {
                        matchedId = id
                        break
                    }
                    end -= 1
                }
                if (matchedId == null) {
                    isBad = true
                    break
                }
                subTokenIds.add(matchedId)
                start = end
            }

            if (isBad) output.add(UNK_ID) else output.addAll(subTokenIds)
        }

        return output
    }

    private fun isPunctuation(char: Char): Boolean {
        val cp = char.code
        // ASCII punctuation ranges plus any Unicode punctuation category.
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        return when (Character.getType(char)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isControl(char: Char): Boolean {
        if (char == '\t' || char == '\n' || char == '\r') return false
        val cp = char.code
        return cp == 0 || (cp in 0x01..0x08) || (cp in 0x0E..0x1F) || (cp in 0x7F..0x9F)
    }

    private fun isCjk(char: Char): Boolean {
        val cp = char.code
        return (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) ||
            (cp in 0xF900..0xFAFF) || (cp in 0x2F800..0x2FA1F)
    }
}
