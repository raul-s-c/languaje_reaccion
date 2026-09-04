package com.raulsc.lenguareaccion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class OpenAiSecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("openai_secret", Context.MODE_PRIVATE)

    fun hasKey(): Boolean = readKey().isNotBlank()

    fun saveKey(value: String) {
        val normalized = value.trim()
        require(normalized.startsWith("sk-")) { "La clave debe comenzar por sk-" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun readKey(): String = runCatching {
        val encrypted = preferences.getString("ciphertext", null) ?: return ""
        val iv = preferences.getString("iv", null) ?: return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "lengua_reaccion_openai_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

data class StudyEnrichment(
    val index: Int,
    val correctedJapanese: String,
    val spanish: String,
    val reading: String,
)

object OpenAiStudyService {
    private const val MODEL = "gpt-5.4-mini"
    private const val ENDPOINT = "https://api.openai.com/v1/responses"
    private const val BATCH_SIZE = 35

    suspend fun enrich(
        apiKey: String,
        segments: List<SubtitleSegment>,
        progress: (Int) -> Unit,
    ): List<SubtitleSegment> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext segments
        val updated = segments.toMutableList()
        segments.indices.chunked(BATCH_SIZE).forEachIndexed { batchIndex, indices ->
            val input = JSONArray().apply {
                indices.forEach { index ->
                    put(JSONObject().put("index", index).put("japanese", segments[index].japanese))
                }
            }
            request(apiKey, input).forEach { item ->
                if (item.index in updated.indices) {
                    updated[item.index] = updated[item.index].copy(
                        japanese = item.correctedJapanese.ifBlank { updated[item.index].japanese },
                        spanish = item.spanish,
                        reading = item.reading,
                    )
                }
            }
            progress((((batchIndex + 1) * 100) / ((segments.size + BATCH_SIZE - 1) / BATCH_SIZE)))
        }
        updated
    }

    private fun request(apiKey: String, inputSegments: JSONArray): List<StudyEnrichment> {
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray().put("segments"))
            .put("properties", JSONObject().put(
                "segments",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject()
                        .put("type", "object")
                        .put("additionalProperties", false)
                        .put(
                            "required",
                            JSONArray()
                                .put("index")
                                .put("correctedJapanese")
                                .put("spanish")
                                .put("reading"),
                        )
                        .put("properties", JSONObject()
                            .put("index", JSONObject().put("type", "integer"))
                            .put("correctedJapanese", JSONObject().put("type", "string"))
                            .put("spanish", JSONObject().put("type", "string"))
                            .put("reading", JSONObject().put("type", "string")))),
            ))

        val body = JSONObject()
            .put("model", MODEL)
            .put("store", false)
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("instructions", INSTRUCTIONS)
            .put("input", inputSegments.toString())
            .put("text", JSONObject().put(
                "format",
                JSONObject()
                    .put("type", "json_schema")
                    .put("name", "japanese_study_segments")
                    .put("strict", true)
                    .put("schema", schema),
            ))

        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", "LenguaReaccion/${BuildConfig.VERSION_NAME}")
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

        val status = connection.responseCode
        val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(status in 200..299) {
            val message = runCatching {
                JSONObject(raw).getJSONObject("error").getString("message")
            }.getOrDefault("HTTP $status")
            "OpenAI: $message"
        }

        val response = JSONObject(raw)
        val outputText = buildString {
            val output = response.getJSONArray("output")
            for (outputIndex in 0 until output.length()) {
                val item = output.getJSONObject(outputIndex)
                val content = item.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    val part = content.getJSONObject(contentIndex)
                    if (part.optString("type") == "output_text") append(part.optString("text"))
                }
            }
        }
        check(outputText.isNotBlank()) { "OpenAI no devolvió texto estructurado" }
        val array = JSONObject(outputText).getJSONArray("segments")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    StudyEnrichment(
                        index = item.getInt("index"),
                        correctedJapanese = item.getString("correctedJapanese"),
                        spanish = item.getString("spanish"),
                        reading = item.getString("reading"),
                    ),
                )
            }
        }
    }

    private const val INSTRUCTIONS = """
Eres un especialista en japonés para estudiantes hispanohablantes. Recibirás segmentos numerados
procedentes de reconocimiento de voz. Conserva exactamente cada índice. Corrige solo errores claros
de reconocimiento usando el contexto del lote, sin inventar diálogo. Devuelve una traducción natural
al español y una lectura íntegra en hiragana; mantén signos, nombres y matices. Si el texto ya es
correcto, repítelo sin cambios. No añadas explicaciones fuera del esquema.
"""
}
