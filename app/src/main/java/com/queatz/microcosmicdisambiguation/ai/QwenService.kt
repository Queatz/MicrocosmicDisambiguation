package com.queatz.microcosmicdisambiguation.ai

import android.net.http.HttpResponseCache.install
import com.queatz.microcosmicdisambiguation.data.StudyGuide
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Talks to the Qwen (Alibaba DashScope) chat completions API, which is OpenAI-compatible, to turn
 * a free-form "IRL situation" into a structured Mandarin Chinese [StudyGuide].
 */
class QwenService(
    private val apiKey: String = QWEN_API_KEY,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 240_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 240_000
        }
    }

    /**
     * Generates a study guide for the given situation.
     *
     * The model is asked to return a single JSON object matching [StudyGuide]: a title plus exactly
     * 3 sentence structures, each with 4-8 words.
     */
    suspend fun generateStudyGuide(situation: String): StudyGuide {
        val request = ChatRequest(
            model = MODEL,
            responseFormat = ResponseFormat(type = "json_object"),
            messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = userPrompt(situation)),
            ),
        )

        val response: ChatResponse = client.post(ENDPOINT) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

        val content = response.choices.firstOrNull()?.message?.content
            ?: error("Empty response from Qwen")

        return json.decodeFromString(StudyGuide.serializer(), content)
    }

    /**
     * Synthesizes [text] to spoken Mandarin using the Qwen3-TTS model and returns the raw audio
     * bytes (a WAV file) so the caller can play it back. The DashScope endpoint returns a temporary
     * URL to the generated audio, which this method downloads before returning.
     *
     * Throws if the API call fails or returns no audio URL.
     */
    suspend fun synthesizeSpeech(text: String): ByteArray {
        val request = TtsRequest(
            model = TTS_MODEL,
            input = TtsInput(
                text = text,
                voice = TTS_VOICE,
                languageType = "Chinese",
            ),
        )

        val response: TtsResponse = client.post(TTS_ENDPOINT) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

        val url = response.output?.audio?.url
            ?: error("Qwen TTS returned no audio URL")

        return client.get(url).readBytes()
    }

    private fun userPrompt(situation: String): String =
        "Create a Mandarin Chinese study guide that teaches the learner EXACTLY what they need to " +
            "say in this real-life situation: \"$situation\". The sentences must let the learner " +
            "actually carry out this specific situation, not just talk about the general topic."

    companion object {
        const val QWEN_API_KEY = "sk-6c257c3e09b349bf81c631f31ef6679a"

        private const val ENDPOINT =
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
        private const val MODEL = "qwen-plus"

        private const val TTS_ENDPOINT =
            "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        private const val TTS_MODEL = "qwen3-tts-flash"
        private const val TTS_VOICE = "Cherry"

        private val SYSTEM_PROMPT = """
            You are an expert Mandarin Chinese tutor and curriculum designer. Given a real-life
            situation, you create a study guide that teaches the learner EXACTLY what they need to
            say to handle THAT specific situation.

            The learner enters an in-real-life situation and must learn precisely what to speak in
            it. The study guide fails if it only teaches the general topic instead of the situation.
            For example, for "offer him a banana", a guide that just lists "banana / apple / grape"
            is useless. Instead it must teach the learner to actually offer the banana, e.g.
            "你想吃香蕉吗？" ("Do you want to eat a banana?") or "给你香蕉。" ("Here is a banana for you.").

            Design process (think before writing, do NOT show your thinking):
            1. First, mentally draft the realistic sentences the learner would actually say in this
               exact situation (4-8 words each, natural and high-utility).
            2. Then build each sentence "structure" from those sentences: the "template" is the
               sentence with "___" marking the words a learner swaps in, and "words" are the actual
               content words used to complete it for THIS situation.
            3. Finally, write a detailed, word-by-word "breakdown" of the COMPLETE, assembled
               sentence (the template with its blanks filled in for THIS situation). Walk through
               every word in the exact order it is spoken and define and explain each one.

            Strict alignment (critical):
            - Every "words" entry must be a real, situation-specific content word that genuinely
              belongs in its sentence — never a random off-topic alternative.
            - The correct words to complete each "template" for this exact situation MUST be present
              in that structure's "words" list, so the assembled sentence really expresses the
              situation.
            - Do not pad the list with unrelated vocabulary just to reach a count.

            Respond with ONLY a single JSON object, no markdown, matching this schema exactly:
            {
              "title": string,                       // a short title for the situation
              "icon": string,                        // a single emoji that best represents the whole scenario
              "structures": [                        // EXACTLY 3 sentence structures
                {
                  "template": string,                // a Mandarin sentence using "___" for each blank
                  "english": string,                 // the English meaning, using "___" for the SAME blanks, in the same order as the template
                  "words": [                         // 4 to 8 words that fit the blanks
                    {
                      "hanzi": string,                // simplified Chinese characters
                      "pinyin": string,               // pinyin with tone marks
                      "english": string,              // exact spoken English gloss used in the sentence chunk (not dictionary form)
                      "emoji": string                 // a single emoji that depicts the word
                    }
                  ],
                  "breakdown": [                      // a word-by-word breakdown of the COMPLETE assembled sentence, in spoken order
                    {
                      "hanzi": string,                // this word, in simplified Chinese characters
                      "pinyin": string,               // pinyin with tone marks
                      "english": string,              // exact spoken English gloss for this word/chunk in this sentence
                      "emoji": string,                // a single emoji that depicts the word
                      "explanation": string,          // a short, friendly note on what this word does in the sentence
                      "punctuation": boolean          // true if this entry is a punctuation mark, not a real word
                    }
                  ],
                  "tokens": [                         // the COMPLETE assembled sentence split into individual words, in spoken order
                    string                            // one word (or punctuation mark) of the assembled sentence
                  ],
                  "alignment": [                      // aligned Mandarin/English chunk pairs, in spoken order
                    {
                      "zh": string,                   // a span of the assembled Mandarin sentence (MUST be "" for English-only words like "is"/"a"/"the" that have no Mandarin)
                      "en": string,                   // the EXACT span of the "english" sentence that this Mandarin maps to (may be "" for Mandarin-only particles)
                      "punctuation": boolean          // true if this chunk is a punctuation mark, not a real word
                    }
                  ]
                }
              ]
            }

            Rules:
            - Use simplified Chinese characters.
            - Each "template" must contain at least one "___" blank.
            - Each "english" must contain the same number of "___" blanks as its "template", in
              the same order, so a word dropped into a Mandarin blank fills the matching English blank.
            - Provide between 6 and 12 words per sentence structure.
            - For "breakdown", break the COMPLETE assembled sentence (template with blanks filled in
              for this situation) into its individual words, in the exact order they are spoken.
              Cover every word, including particles and punctuation-bearing words. Each
              "explanation" must be short, beginner-friendly, and explain the word's role in this
              sentence.
            - For "tokens", split the SAME COMPLETE assembled sentence into its individual words in
              spoken order (the same words as "breakdown", including particles and any punctuation).
              Concatenating all "tokens" in order MUST reproduce the assembled sentence exactly.
            - For "alignment", break BOTH the assembled Mandarin sentence and the assembled English
              "english" sentence into aligned chunks, in spoken (Mandarin) order. Each chunk's "zh"
              is a span of the Mandarin sentence and "en" is the EXACT matching span of the English
              sentence, so a learner can see which English words correspond to each Mandarin word.
              Concatenating all "zh" in order MUST reproduce the assembled Mandarin sentence exactly,
              and concatenating all "en" in order MUST reproduce the assembled English sentence
              exactly (including spaces). When a Mandarin word has no English equivalent (e.g. the
              particle "吗"), use "en": "". When an English word has no Mandarin equivalent (e.g.
              the copula "is", or "a"/"the"), put it in its OWN separate chunk with "zh": "" — do
              NOT attach it to a neighbouring Mandarin word, otherwise that Mandarin word would
              wrongly highlight the unrelated English filler. Align purely by MEANING, never by
              position: e.g. for Mandarin "这抹茶真好吃" / English "This matcha is really delicious",
              "真" must map to "really" (not "is"), and "is" must be its own chunk with "zh": "".
            - Set "punctuation": true on any "breakdown" or "alignment" entry that is a punctuation
              mark (e.g. "，", "。", "？") rather than a real spoken word; otherwise set it to false.
            - CRITICAL for translation quality: for every word-level English field ("words[].english"
              and "breakdown[].english"), use the exact spoken English form for that mapped chunk in
              the sentence, not a dictionary label. Do NOT add helper words that are not in that
              chunk. Example: use "fall" (not "to fall"), "eat" (not "to eat"), "banana" (not "a banana"
              unless the mapped chunk literally includes "a banana").
            - Keep it beginner friendly, but make every sentence directly usable IN the situation.
            - Use a register appropriate to the situation (e.g. polite forms with strangers).
        """.trimIndent()
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("response_format") val responseFormat: ResponseFormat,
)

@Serializable
private data class ResponseFormat(
    val type: String,
)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatResponse(
    val choices: List<Choice> = emptyList(),
)

@Serializable
private data class Choice(
    val message: ChatMessage,
)

@Serializable
private data class TtsRequest(
    val model: String,
    val input: TtsInput,
)

@Serializable
private data class TtsInput(
    val text: String,
    val voice: String,
    @SerialName("language_type") val languageType: String,
)

@Serializable
private data class TtsResponse(
    val output: TtsOutput? = null,
)

@Serializable
private data class TtsOutput(
    val audio: TtsAudio? = null,
)

@Serializable
private data class TtsAudio(
    val url: String? = null,
)
