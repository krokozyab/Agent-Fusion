package com.orchestrator.context.embedding

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BertTokenizerTest {

    private val tokenizer = BertTokenizer()

    @Test
    fun `vocabulary loads the full bert-base-uncased vocab`() {
        // Resource-backed vocabulary must be the real 30522-token vocab, not the stub.
        assertEquals(30522, BertTokenizer.defaultVocabulary.size)
    }

    @Test
    fun `known words map to canonical bert-base-uncased ids`() {
        // Canonical ids from bert-base-uncased vocab.txt (line number - 1).
        assertEquals(BertTokenizer.CLS_ID, BertTokenizer.defaultVocabulary["[CLS]"])
        assertEquals(BertTokenizer.SEP_ID, BertTokenizer.defaultVocabulary["[SEP]"])
        assertEquals(1996, BertTokenizer.defaultVocabulary["the"])
        assertEquals(7592, BertTokenizer.defaultVocabulary["hello"])
        assertEquals(2088, BertTokenizer.defaultVocabulary["world"])
    }

    @Test
    fun `tokenize wraps output in CLS and SEP`() {
        val ids = tokenizer.tokenize("hello world")
        assertEquals(BertTokenizer.CLS_ID, ids.first())
        assertEquals(BertTokenizer.SEP_ID, ids.last())
        // hello, world are both single vocab entries → [CLS] hello world [SEP]
        assertEquals(listOf(101, 7592, 2088, 102), ids.toList())
    }

    @Test
    fun `wordpiece splits out-of-vocab words into subtokens`() {
        // "embedding" is not a single token; it splits into em ##bed ##ding.
        val ids = tokenizer.tokenize("embedding").toList()
        assertEquals(BertTokenizer.CLS_ID, ids.first())
        assertEquals(BertTokenizer.SEP_ID, ids.last())
        // Must be more than just [CLS] [UNK] [SEP] — real subword decomposition.
        assertTrue(ids.size > 3, "Expected subword split, got $ids")
        assertTrue(ids.none { it == BertTokenizer.UNK_ID }, "Should not contain [UNK], got $ids")
    }

    @Test
    fun `punctuation is split into separate tokens`() {
        // "don't" → don ' t ; punctuation isolated, no whitespace dependency.
        val ids = tokenizer.tokenize("foo.bar").toList()
        // foo, ., bar each resolvable → length > 3 (CLS + 3 + SEP at least)
        assertTrue(ids.size >= 5, "Expected punctuation split, got $ids")
    }

    @Test
    fun `empty and blank text yield just CLS and SEP`() {
        assertEquals(listOf(101, 102), tokenizer.tokenize("").toList())
        assertEquals(listOf(101, 102), tokenizer.tokenize("   \n\t").toList())
    }

    @Test
    fun `accents are stripped to match uncased model`() {
        // "café" → "cafe" after accent stripping; should match the "cafe" path,
        // not collapse to [UNK].
        val ids = tokenizer.tokenize("café").toList()
        assertTrue(ids.none { it == BertTokenizer.UNK_ID }, "Accented word should not be [UNK], got $ids")
    }

    @Test
    fun `sequence is truncated to max length`() {
        val small = BertTokenizer(maxSequenceLength = 8)
        val ids = small.tokenize("the the the the the the the the the the the the")
        assertTrue(ids.size <= 8, "Expected truncation to <=8, got ${ids.size}")
        assertEquals(BertTokenizer.SEP_ID, ids.last(), "Truncated sequence must still end with [SEP]")
    }
}
