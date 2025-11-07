package com.orchestrator.context.embedding

import kotlin.math.min

/**
 * BERT-compatible tokenizer that implements proper tokenization for sentence transformer models.
 * Supports both basic BERT and WordPiece tokenization.
 */
class BertTokenizer(
    private val vocabulary: Map<String, Int> = defaultBertVocabulary,
    private val maxSequenceLength: Int = 512
) {
    companion object {
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

        // Create default BERT vocabulary (common tokens)
        val defaultBertVocabulary: Map<String, Int> by lazy {
            mapOf(
                PAD_TOKEN to PAD_ID,
                "[unused1]" to 1,
                "[unused2]" to 2,
                CLS_TOKEN to CLS_ID,
                UNK_TOKEN to UNK_ID,
                MASK_TOKEN to MASK_ID,
                SEP_TOKEN to SEP_ID
            ).also { vocab ->
                // Add common tokens and sub-token prefixes
                var id = 1000
                for (word in listOf(
                    "the", "a", "of", "to", "and", "in", "is", "for", "it", "on",
                    "as", "was", "be", "by", "with", "at", "from", "this", "that", "or",
                    "an", "are", "but", "have", "has", "had", "do", "does", "did", "will",
                    "would", "should", "could", "may", "might", "must", "can", "##ing",
                    "##ed", "##er", "##ly", "##tion", "##ity", "##ment", "##able"
                )) {
                    if (!vocab.containsKey(word) && id < 30522) {
                        vocab as MutableMap
                        vocab[word] = id
                        id++
                    }
                }
            }
        }
    }

    /**
     * Tokenize text into BERT token IDs
     * Returns: [CLS] + tokens + [SEP]
     */
    fun tokenize(text: String): IntArray {
        val tokens = mutableListOf<Int>()
        
        // Add [CLS] token
        tokens.add(CLS_ID)
        
        // Tokenize the text
        val basicTokens = basicTokenize(text)
        val wordpieceTokens = wordpieceTokenize(basicTokens)
        
        // Add tokens up to max sequence length - 1 (reserve space for [SEP])
        for (token in wordpieceTokens.take(maxSequenceLength - 2)) {
            tokens.add(token)
        }
        
        // Add [SEP] token
        tokens.add(SEP_ID)
        
        return tokens.toIntArray()
    }

    /**
     * Basic tokenization: lowercase, whitespace, punctuation splitting
     */
    private fun basicTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var current = StringBuilder()
        
        val cleaned = text.lowercase().trim()
        
        for (char in cleaned) {
            when {
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current = StringBuilder()
                    }
                }
                isPunctuation(char) || isControl(char) -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current = StringBuilder()
                    }
                    if (!isControl(char)) {
                        tokens.add(char.toString())
                    }
                }
                else -> {
                    current.append(char)
                }
            }
        }
        
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        
        return tokens
    }

    /**
     * WordPiece tokenization: split words into subword tokens using vocabulary
     */
    private fun wordpieceTokenize(tokens: List<String>): List<Int> {
        val output = mutableListOf<Int>()
        
        for (token in tokens) {
            if (vocabulary.containsKey(token)) {
                // Exact match in vocabulary
                output.add(vocabulary[token]!!)
            } else {
                // Try to split with ##prefix notation
                val subtokens = splitWordpiece(token)
                if (subtokens.isEmpty()) {
                    // Unknown token
                    output.add(UNK_ID)
                } else {
                    for (subtoken in subtokens) {
                        output.add(vocabulary[subtoken] ?: UNK_ID)
                    }
                }
            }
        }
        
        return output
    }

    /**
     * Split a word into subword tokens using greedy approach
     */
    private fun splitWordpiece(word: String): List<String> {
        val tokens = mutableListOf<String>()
        var start = 0
        
        while (start < word.length) {
            var end = word.length
            var found = false
            
            // Try to find the longest subword
            while (start < end) {
                var substr = word.substring(start, end)
                if (start > 0) {
                    substr = "##$substr"
                }
                
                if (vocabulary.containsKey(substr)) {
                    tokens.add(substr)
                    found = true
                    break
                }
                
                end -= 1
            }
            
            if (!found) {
                // Character not found in vocabulary
                return emptyList()
            }
            
            start = end
        }
        
        return tokens
    }

    /**
     * Check if character is punctuation
     */
    private fun isPunctuation(char: Char): Boolean {
        val cp = char.code
        return (cp >= 33 && cp <= 47) ||  // !-/
               (cp >= 58 && cp <= 64) ||  // :-@
               (cp >= 91 && cp <= 96) ||  // [-`
               (cp >= 123 && cp <= 126)   // {-~
    }

    /**
     * Check if character is control character
     */
    private fun isControl(char: Char): Boolean {
        val cp = char.code
        return cp == 0x0009 || cp == 0x000A || cp == 0x000D ||
               (cp >= 0x0000 && cp <= 0x0008) ||
               (cp >= 0x000E && cp <= 0x001F) ||
               (cp >= 0x007F && cp <= 0x009F)
    }
}
