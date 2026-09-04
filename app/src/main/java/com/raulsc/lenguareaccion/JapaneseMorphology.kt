package com.raulsc.lenguareaccion

import com.atilika.kuromoji.ipadic.Tokenizer

data class JapaneseToken(
    val surface: String,
    val reading: String,
    val baseForm: String,
    val partOfSpeech: String,
)

/** Offline IPADIC-backed segmentation, readings and dictionary forms. */
object JapaneseMorphology {
    private val tokenizer: Tokenizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Tokenizer() }

    fun analyze(text: String): List<JapaneseToken> = tokenizer.tokenize(text).map { token ->
        JapaneseToken(
            surface = token.surface,
            reading = token.reading.takeUnless { it == "*" }?.katakanaToHiragana().orEmpty(),
            baseForm = token.baseForm.takeUnless { it == "*" } ?: token.surface,
            partOfSpeech = token.partOfSpeechLevel1.takeUnless { it == "*" }.orEmpty(),
        )
    }

    private fun String.katakanaToHiragana(): String = buildString(length) {
        this@katakanaToHiragana.forEach { character ->
            append(
                if (character.code in 0x30A1..0x30F6) (character.code - 0x60).toChar()
                else character,
            )
        }
    }
}
