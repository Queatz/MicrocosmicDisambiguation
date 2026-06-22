package com.queatz.microcosmicdisambiguation.ai

import android.net.http.HttpResponseCache.install
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.LanguagePair
import com.queatz.microcosmicdisambiguation.data.Mission
import com.queatz.microcosmicdisambiguation.data.MissionReply
import com.queatz.microcosmicdisambiguation.data.MissionSet
import com.queatz.microcosmicdisambiguation.data.MissionTurn
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
     * 3 sentence structures, each with exactly 6 fill-in words.
     */
    suspend fun generateStudyGuide(situation: String, languages: LanguagePair): StudyGuide {
        val request = ChatRequest(
            model = MODEL,
            responseFormat = ResponseFormat(type = "json_object"),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt(languages)),
                ChatMessage(role = "user", content = userPrompt(situation, languages)),
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
     * Generates a set of 3 real-life "missions" that simulate situations the learner can navigate
     * using exactly what the [guide] taught them. Each mission is a short role-play with a handful
     * of multiple-choice turns; the correct option at every turn is built from the guide's own
     * sentences and words, so the learner is rewarded for what they studied.
     */
    suspend fun generateMissions(guide: StudyGuide, languages: LanguagePair): MissionSet {
        val request = ChatRequest(
            model = MODEL,
            responseFormat = ResponseFormat(type = "json_object"),
            messages = listOf(
                ChatMessage(role = "system", content = missionsSystemPrompt(languages)),
                ChatMessage(role = "user", content = missionsUserPrompt(guide, languages)),
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

        return json.decodeFromString(MissionSet.serializer(), content)
    }

    /**
     * Reacts to a free-form spoken reply the learner gave at a mission [turn]. The learner's
     * [transcript] (what the speech recognizer heard them say, in the target language) is turned
     * into a [MissionReply] that (a) translates their own utterance back into the language they
     * know so they can verify they said what they meant, and (b) generates a realistic, in-character
     * response from the other person ([Mission.role]) that is contingent on what the learner
     * actually said — advancing the role-play like a real conversation rather than scoring a
     * right/wrong answer.
     *
     * [history] is the running list of prior reply exchanges this mission, oldest first, so the
     * other person stays consistent across turns.
     */
    suspend fun generateMissionReply(
        mission: Mission,
        turn: MissionTurn,
        transcript: String,
        languages: LanguagePair,
        history: List<MissionReply> = emptyList(),
    ): MissionReply {
        val request = ChatRequest(
            model = MODEL,
            responseFormat = ResponseFormat(type = "json_object"),
            messages = listOf(
                ChatMessage(role = "system", content = missionReplySystemPrompt(languages)),
                ChatMessage(
                    role = "user",
                    content = missionReplyUserPrompt(mission, turn, transcript, languages, history),
                ),
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

        return json.decodeFromString(MissionReply.serializer(), content)
    }

    /**
     * Synthesizes [text] to spoken Mandarin using the Qwen3-TTS model and returns the raw audio
     * bytes (a WAV file) so the caller can play it back. The DashScope endpoint returns a temporary
     * URL to the generated audio, which this method downloads before returning.
     *
     * Throws if the API call fails or returns no audio URL.
     */
    suspend fun synthesizeSpeech(text: String, language: Language): ByteArray {
        val request = TtsRequest(
            model = TTS_MODEL,
            input = TtsInput(
                text = text,
                voice = language.ttsVoice,
                languageType = language.ttsLanguageType,
            ),
            parameters = TtsParameters(
                instructions = TTS_INSTRUCTIONS,
                optimizeInstructions = true,
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

    private fun userPrompt(situation: String, languages: LanguagePair): String =
        "Create a ${languages.target.displayName} study guide that teaches the learner EXACTLY " +
            "what they need to say in this real-life situation: \"$situation\". The sentences must " +
            "let the learner actually carry out this specific situation, not just talk about the " +
            "general topic."

    /**
     * Summarizes the studied [guide] (its title plus every sentence the learner practiced) so the
     * model can build missions whose correct answers come straight from what was studied.
     */
    private fun missionsUserPrompt(guide: StudyGuide, languages: LanguagePair): String {
        val target = languages.target.displayName
        val source = languages.source.displayName
        val lines = guide.structures.joinToString("\n") { structure ->
            val words = structure.words.joinToString(", ") { "${it.hanzi} (${it.english})" }
            "- $target: \"${structure.template}\" | $source: \"${structure.english}\" | words: $words"
        }
        return "The learner just studied this $target lesson titled \"${guide.title}\".\n" +
            "These are the sentence patterns and words they practiced:\n$lines\n\n" +
            "Create 3 real-life missions that simulate situations where the learner would use " +
            "EXACTLY these sentences and words. The correct choice at every turn must be a " +
            "sentence the learner can build from the patterns and words above, so they succeed by " +
            "using what they just studied."
    }

    /**
     * Frames a single open-ended turn for the model: the scene, who the other person is, their last
     * line, what the learner is trying to do this turn, the conversation so far, and finally the
     * exact words the speech recognizer heard the learner say. The model uses all of this to react
     * in character to what the learner ACTUALLY said.
     */
    private fun missionReplyUserPrompt(
        mission: Mission,
        turn: MissionTurn,
        transcript: String,
        languages: LanguagePair,
        history: List<MissionReply>,
    ): String {
        val target = languages.target.displayName
        val source = languages.source.displayName
        val builder = StringBuilder()
        builder.append("Scene ($source): \"${mission.setting}\".\n")
        if (mission.role.isNotBlank()) {
            builder.append("You are role-playing as: ${mission.role}.\n")
        }
        if (turn.speaker.isNotBlank()) {
            builder.append("Your last line to the learner ($target): \"${turn.speaker}\".\n")
        }
        if (turn.prompt.isNotBlank()) {
            builder.append("What the learner is trying to do this turn ($source): \"${turn.prompt}\".\n")
        }
        if (history.isNotEmpty()) {
            builder.append("Conversation so far (oldest first):\n")
            history.forEach { exchange ->
                builder.append("- Learner said ($source): \"${exchange.translation}\" → ")
                builder.append("You replied ($target): \"${exchange.reply}\"\n")
            }
        }
        builder.append(
            "The learner just spoke this $target reply (as heard by speech recognition): " +
                "\"$transcript\".\n",
        )
        builder.append(
            "Translate exactly what the learner said into $source, then reply in character in " +
                "$target as the other person, reacting to what they actually said.",
        )
        return builder.toString()
    }

    companion object {
        const val QWEN_API_KEY = "sk-6c257c3e09b349bf81c631f31ef6679a"

        private const val ENDPOINT =
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
        private const val MODEL = "qwen-plus"

        private const val TTS_ENDPOINT =
            "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        private const val TTS_MODEL = "qwen3-tts-instruct-flash"

        /**
         * Steers the qwen3-tts-instruct-flash model to read every line with upbeat excitement: an
         * energetic, cheerful tone with a lively pace and bright, rising intonation.
         */
        private const val TTS_INSTRUCTIONS =
            "Speak with upbeat excitement: an energetic, cheerful and enthusiastic tone, a lively " +
                "pace, and bright, rising intonation, as if eager to share something amazing!"

        private fun systemPrompt(languages: LanguagePair): String {
            val target = languages.target.displayName
            val source = languages.source.displayName
            val script = languages.target.scriptDescription
            val romanizationField = if (languages.target.usesRomanization) {
                "a romanization (pronunciation guide) for this word"
            } else {
                "leave this an empty string \"\"; $target needs no separate romanization"
            }
            val romanizationRule = if (languages.target.usesRomanization) {
                "Provide an accurate romanization in every \"romanization\" field."
            } else {
                "Leave every \"romanization\" field as an empty string \"\"; $target is written in " +
                    "the Latin alphabet and needs no separate romanization."
            }
            return """
            You are an expert $target tutor and curriculum designer. The learner already knows
            $source and wants to learn $target. Given a real-life situation, you create a study
            guide that teaches the learner EXACTLY what they need to say to handle THAT specific
            situation.

            The learner enters an in-real-life situation and must learn precisely what to speak in
            it. The study guide fails if it only teaches the general topic instead of the situation.
            For example, for "offer him a banana", a guide that just lists "banana / apple / grape"
            is useless. Instead it must teach the learner to actually offer the banana, e.g. the
            $target for "Do you want to eat a banana?" or "Here is a banana for you.".

            Write all $target content in $script. Write every translation, explanation, and gloss in
            $source.

            Design process (think before writing, do NOT show your thinking):
            1. First, mentally draft the realistic sentences the learner would actually say in this
               exact situation (natural and high-utility). Each sentence must be built from 2 "___"
               blanks plus 2-3 fixed words that form the sentence structure (the words that stay the
               same and hold the sentence together around the blanks).
            2. Then build each sentence "structure" from those sentences: the "template" is the
               sentence with 2 "___" marking the words a learner swaps in, surrounded by 2-3 fixed
               structural words, and "words" are the actual content words used to complete it for
               THIS situation.
            3. Finally, write a detailed, word-by-word "breakdown" of the COMPLETE, assembled
               sentence (the template with its blanks filled in for THIS situation). Walk through
               every word in the exact order it is spoken and define and explain each one.

            Strict alignment (critical):
            - Every "words" entry must be a real, situation-specific content word that genuinely
              belongs in its sentence — never a random off-topic alternative.
            - The correct words to complete each "template" for this exact situation MUST be present
              in that structure's "words" list, so the assembled sentence really expresses the
              situation.

            Respond with ONLY a single JSON object, no markdown, matching this schema exactly:
            {
              "title": string,                       // a short title for the situation, in $source
              "icon": string,                        // a single emoji that best represents the whole scenario
              "structures": [                        // EXACTLY 3 sentence structures
                {
                  "template": string,                // a $target sentence using "___" for each of the 2 blanks
                  "translation": string,             // the $source meaning, using "___" for the SAME 2 blanks, in the same order as the template
                  "words": [                         // EXACTLY 6 words that fit the blanks
                    {
                      "text": string,                 // the word in $target ($script)
                      "romanization": string,         // $romanizationField
                      "translation": string,          // exact spoken $source gloss used in the sentence chunk (not dictionary form)
                      "emoji": string                 // a single emoji that depicts the word
                    }
                  ],
                  "breakdown": [                      // a word-by-word breakdown of the COMPLETE assembled sentence, in spoken order
                    {
                      "text": string,                 // this word, in $target ($script)
                      "romanization": string,         // $romanizationField
                      "translation": string,          // exact spoken $source gloss for this word/chunk in this sentence
                      "emoji": string,                // a single emoji that depicts the word
                      "explanation": string,          // a short, friendly note (in $source) on what this word does in the sentence
                      "punctuation": boolean          // true if this entry is a punctuation mark, not a real word
                    }
                  ],
                  "tokens": [                         // the COMPLETE assembled sentence split into individual words, in spoken order
                    string                            // one word (or punctuation mark) of the assembled sentence
                  ],
                  "alignment": [                      // aligned $target/$source chunk pairs, in spoken order
                    {
                      "target": string,               // a span of the assembled $target sentence (MUST be "" for $source-only words that have no $target)
                      "source": string,               // the EXACT span of the "translation" sentence that this $target maps to (may be "" for $target-only particles)
                      "punctuation": boolean          // true if this chunk is a punctuation mark, not a real word
                    }
                  ]
                }
              ]
            }

            Rules:
            - Write all $target text in $script.
            - Use ultra-beginner friendly $target.
            - Each "template" must contain exactly 2 "___" blanks plus 2-3 fixed words that form
              the sentence structure (the non-blank words that stay the same around the blanks).
            - Each "translation" must contain the same number of "___" blanks as its "template", in
              the same order, so a word dropped into a $target blank fills the matching $source blank.
            - Provide EXACTLY 6 words per sentence structure.
            - $romanizationRule
            - For "breakdown", break the COMPLETE assembled sentence (template with blanks filled in
              for this situation) into its individual words, in the exact order they are spoken.
              Cover every word, including particles and punctuation-bearing words. Each
              "explanation" must be short, beginner-friendly, and explain the word's role in this
              sentence.
            - For "tokens", split the SAME COMPLETE assembled sentence into its individual words in
              spoken order (the same words as "breakdown", including particles and any punctuation).
              Concatenating all "tokens" in order MUST reproduce the assembled sentence exactly.
            - For "alignment", break BOTH the assembled $target sentence and the assembled $source
              "translation" sentence into aligned chunks, in spoken ($target) order. Each chunk's
              "target" is a span of the $target sentence and "source" is the EXACT matching span of
              the $source sentence, so a learner can see which $source words correspond to each
              $target word. Concatenating all "target" in order MUST reproduce the assembled $target
              sentence exactly, and concatenating all "source" in order MUST reproduce the assembled
              $source sentence exactly (including spaces). When a $target word has no $source
              equivalent (e.g. a grammatical particle), use "source": "". When a $source word has no
              $target equivalent (e.g. an article like "a"/"the", or a copula), put it in its OWN
              separate chunk with "target": "" — do NOT attach it to a neighbouring $target word,
              otherwise that $target word would wrongly highlight the unrelated $source filler. Align
              purely by MEANING, never by position.
            - Set "punctuation": true on any "breakdown" or "alignment" entry that is a punctuation
              mark rather than a real spoken word; otherwise set it to false.
            - CRITICAL for translation quality: for every word-level $source field
              ("words[].translation" and "breakdown[].translation"), use the exact spoken $source
              form for that mapped chunk in the sentence, not a dictionary label, and without
              additional contextual meanings. Example: use "fall" (not "to fall"), "eat" (not "to
              eat").
            - Keep it beginner friendly, but make every sentence directly usable IN the situation.
            - Use a register appropriate to the situation (e.g. polite forms with strangers).
        """.trimIndent()
        }

        private fun missionsSystemPrompt(languages: LanguagePair): String {
            val target = languages.target.displayName
            val source = languages.source.displayName
            val script = languages.target.scriptDescription
            val romanizationField = if (languages.target.usesRomanization) {
                "a romanization (pronunciation guide)"
            } else {
                "leave empty (\"\"); $target needs no separate romanization"
            }
            return """
            You are an expert $target tutor who designs interactive role-play missions for a learner
            who already knows $source. Given the sentences and words a learner just studied, you
            create 3 short, realistic "missions": situations the learner finds themselves in and
            must NAVIGATE by choosing the right thing to say in $target, using EXACTLY what they
            studied.

            Each mission is a tiny conversation. On each turn the other person says one line, and the
            learner picks what to say back from a few choices. The learner navigates the mission
            successfully by picking the correct line every turn.

            Design rules (think before writing, do NOT show your thinking):
            - Build the CORRECT choice at every turn from the studied sentence patterns and words.
              The learner must be able to win using only what they studied.
            - The other (incorrect) choices must be plausible real $target lines, but clearly wrong
              for THIS moment in the conversation (e.g. answering a different question, too rude, or
              off-topic). Never make them gibberish.
            - Keep every $target line short, natural, and beginner friendly. Write it in $script and
              use a register appropriate to the situation.
            - Make the 3 missions feel like 3 different real moments (e.g. different people, places,
              or goals) that all reuse the studied language.

            Respond with ONLY a single JSON object, no markdown, matching this schema exactly:
            {
              "missions": [                          // EXACTLY 3 missions
                {
                  "title": string,                   // a short title for the scene, in $source
                  "icon": string,                    // a single emoji representing the scene
                  "setting": string,                 // one $source sentence: where the learner is and what is happening
                  "role": string,                    // who the other person is, in $source (e.g. "a taxi driver")
                  "turns": [                          // 1-3 conversation turns, in order
                    {
                      "speaker": string,              // the other person's line in $target ("" only for an opening turn where the learner speaks first)
                      "speakerRomanization": string,  // $romanizationField for "speaker" ("" if speaker is "")
                      "speakerTranslation": string,   // $source meaning of "speaker" ("" if speaker is "")
                      "emoji": string,                // a single emoji depicting the other person or what they are doing
                      "prompt": string,               // a short $source nudge telling the learner what to do this turn
                      "choices": [                    // 3 choices the learner picks between
                        {
                          "text": string,             // the $target line, in $script
                          "romanization": string,     // $romanizationField
                          "translation": string,      // $source meaning of the line
                          "correct": boolean,         // true for EXACTLY ONE choice per turn
                          "reply": string             // one $source sentence: how the other person reacts to this choice
                        }
                      ]
                    }
                  ],
                  "success": string                  // a short, warm $source line celebrating that the learner navigated the situation
                }
              ]
            }

            Rules:
            - EXACTLY 3 missions.
            - 1 to 3 turns per mission.
            - EXACTLY 3 choices per turn, with EXACTLY ONE "correct": true.
            - Write all $target text in $script with ultra-beginner-friendly $target.
            - Every "reply" is in $source and reacts to that specific choice. For the CORRECT choice,
              "reply" MUST describe the positive outcome: the good thing that happens because the
              learner said the right thing (the other person responds well and the situation moves
              forward). For the wrong choices, gently explain what went wrong. Never leave the
              correct choice's "reply" empty.
            - The correct choices must come from the sentences and words the learner studied.
        """.trimIndent()
        }

        private fun missionReplySystemPrompt(languages: LanguagePair): String {
            val target = languages.target.displayName
            val source = languages.source.displayName
            val script = languages.target.scriptDescription
            val romanizationField = if (languages.target.usesRomanization) {
                "a romanization (pronunciation guide) for \"reply\""
            } else {
                "leave empty (\"\"); $target needs no separate romanization"
            }
            return """
            You are role-playing as a real person in a $target conversation with a learner who
            already knows $source and is practicing $target by speaking freely. The learner just
            spoke a spontaneous reply (transcribed by speech recognition, so it may contain small
            errors or be only a fragment). You react like a real person would in this moment — there
            is no "correct" answer; judge plausibility and effort, never right vs. wrong.

            Your job each turn:
            1. Translate EXACTLY what the learner said into natural $source, so they can confirm they
               said what they meant (preserve their actual meaning, do not "fix" or improve it).
            2. Reply in character, in $target, in a way that is logically contingent on what the
               learner ACTUALLY said — react with agreement, surprise, a question, a counter-offer,
               etc., advancing the scene as if it were a real event. Keep it short, natural, and
               beginner friendly.

            Handle these cases gracefully:
            - If the learner was silent, spoke in $source instead of $target, or went wildly
              off-topic, set "onTopic" to false and make your "reply" gently steer them back, e.g.
              ask them to repeat that in $target.
            - If the reply is only a partial sentence or fragment, still react plausibly to the most
              likely intended meaning and set "onTopic" to true.

            Write all $target text in $script. Write all $source text in $source.

            Respond with ONLY a single JSON object, no markdown, matching this schema exactly:
            {
              "translation": string,        // what the learner said, translated into $source
              "reply": string,              // the other person's in-character response, in $target ($script)
              "replyRomanization": string,  // $romanizationField
              "replyTranslation": string,   // the $source meaning of "reply"
              "onTopic": boolean            // false only when the learner was silent, used $source, or went wildly off-context
            }
        """.trimIndent()
        }
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
    val parameters: TtsParameters? = null,
)

@Serializable
private data class TtsInput(
    val text: String,
    val voice: String,
    @SerialName("language_type") val languageType: String,
)

@Serializable
private data class TtsParameters(
    val instructions: String,
    @SerialName("optimize_instructions") val optimizeInstructions: Boolean,
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
