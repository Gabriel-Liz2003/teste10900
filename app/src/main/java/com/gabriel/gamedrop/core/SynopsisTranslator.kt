package com.gabriel.gamedrop.core

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SynopsisTranslation(
    val text: String,
    val translated: Boolean
)

internal data class ProtectedText(val text: String, val replacements: Map<String, String>)

internal fun protectTranslationTerms(text: String, terms: Collection<String>): ProtectedText {
    var protected = text
    val replacements = linkedMapOf<String, String>()
    terms.asSequence()
        .map(String::trim)
        .filter { it.length >= 2 }
        .distinctBy { it.lowercase() }
        .sortedByDescending(String::length)
        .forEachIndexed { index, term ->
            val token = "ZXGAMEDROP${index}ZX"
            val regex = Regex(Regex.escape(term), RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(protected)) {
                protected = regex.replace(protected, token)
                replacements[token] = term
            }
        }
    return ProtectedText(protected, replacements)
}

internal fun restoreTranslationTerms(text: String, replacements: Map<String, String>): String {
    var restored = text
    replacements.forEach { (token, term) ->
        restored = restored.replace(token, term, ignoreCase = true)
            .replace(token.replace("ZX", "ZX "), term, ignoreCase = true)
    }
    return restored
}

class SynopsisTranslator {
    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()

    suspend fun toPortuguese(text: String, protectedTerms: Collection<String> = emptyList()): SynopsisTranslation {
        if (text.isBlank()) return SynopsisTranslation(text, false)
        val protected = protectTranslationTerms(text, protectedTerms + DEFAULT_PROTECTED_TERMS)
        val detectedTag = runCatching { languageIdentifier.identifyLanguage(text).awaitValue() }
            .getOrDefault("en")
        if (detectedTag.startsWith("pt", ignoreCase = true)) {
            return SynopsisTranslation(text, false)
        }
        val source = if (detectedTag.equals("und", ignoreCase = true)) {
            TranslateLanguage.ENGLISH
        } else {
            TranslateLanguage.fromLanguageTag(detectedTag) ?: TranslateLanguage.ENGLISH
        }
        val translated = translate(protected.text, source)
        return translated.copy(text = restoreTranslationTerms(translated.text, protected.replacements))
    }

    private suspend fun translate(text: String, sourceLanguage: String): SynopsisTranslation {
        if (sourceLanguage == TranslateLanguage.PORTUGUESE) return SynopsisTranslation(text, false)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE)
            .build()
        val translator = Translation.getClient(options)
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitCompletion()
            SynopsisTranslation(translator.translate(text).awaitValue(), true)
        } finally {
            translator.close()
        }
    }

    fun close() = languageIdentifier.close()

    companion object {
        private val DEFAULT_PROTECTED_TERMS = listOf(
            "PlayStation 5", "PlayStation 4", "PlayStation", "PS5", "PS4",
            "Xbox Series X|S", "Xbox Series X", "Xbox Series S", "Xbox One", "Xbox",
            "Nintendo Switch 2", "Nintendo Switch", "Nintendo",
            "Steam", "Epic Games Store", "PC", "iOS", "Android"
        )
    }
}

private suspend fun Task<*>.awaitCompletion() = suspendCancellableCoroutine<Unit> { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

private suspend fun <T> Task<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
