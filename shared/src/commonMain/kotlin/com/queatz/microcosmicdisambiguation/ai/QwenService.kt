package com.queatz.microcosmicdisambiguation.ai

import com.queatz.microcosmicdisambiguation.ai.QwenService.Companion.HOST
import com.queatz.microcosmicdisambiguation.ai.QwenService.Companion.QWEN_API_KEY
import com.queatz.microcosmicdisambiguation.ai.QwenService.Companion.REALTIME_ASR_MODEL
import com.queatz.microcosmicdisambiguation.ai.QwenService.Companion.REALTIME_ENDPOINT
import com.queatz.microcosmicdisambiguation.ai.QwenService.Companion.TTS_DEFAULT_VOICE_ID
import com.queatz.microcosmicdisambiguation.ai.QwenService.Companion.TTS_ENDPOINT
import com.queatz.microcosmicdisambiguation.ai.QwenService.Companion.TTS_MODEL
import com.queatz.microcosmicdisambiguation.ai.QwenService.Companion.TTS_VOICE_DESIGN_ENDPOINT
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.LanguagePair
import com.queatz.microcosmicdisambiguation.data.Mission
import com.queatz.microcosmicdisambiguation.data.MissionReply
import com.queatz.microcosmicdisambiguation.data.MissionSet
import com.queatz.microcosmicdisambiguation.data.MissionTurn
import com.queatz.microcosmicdisambiguation.data.StudyGuide
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Talks to the Qwen (Alibaba DashScope) chat completions API, which is OpenAI-compatible, to turn
 * a free-form "IRL situation" into a structured Mandarin Chinese [StudyGuide].
 */
class QwenService(
    private val apiKey: String = QWEN_API_KEY,
    /**
     * Reads a previously-cached [TTS_MODEL] voice-design `voice_id` override, if any was ever
     * persisted (see [resolveVoiceId]). Most installs have none and simply fall back to
     * [TTS_DEFAULT_VOICE_ID]; this only matters for installs that persisted one back when this
     * service still created a voice at runtime (now removed, see [resolveVoiceId]).
     */
    private val cachedVoiceId: () -> String? = { null },
    /**
     * The `voice_id` of the enabled cloned voice to use for a given [Language], or null when none
     * is enrolled/enabled for it. When present it takes precedence over the default voice-design
     * voice in [resolveVoiceId], so a learner's own cloned voice is what speaks. Defaults to always
     * returning null, i.e. no cloned voices, so the service still works standalone (e.g. in tests).
     */
    private val enabledClonedVoiceId: (Language) -> String? = { null },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // CosyVoice's WebSocket protocol validates the run-task/continue-task/finish-task messages
        // strictly and rejects them if fields with (non-null) default values, such as
        // RunTaskPayload.taskGroup ("task_group"), are missing entirely — but kotlinx.serialization
        // omits any property left at its default value unless explicitly told to encode it. Without
        // this, DashScope replies "Missing required parameter 'payload.task_group'!" (and would fail
        // the same way for every other defaulted field below it, e.g. TtsParameters.format/
        // sampleRate) even though the Kotlin code always sets a value.
        encodeDefaults = true
    }

    private var inMemoryVoiceId: String? = null

    // No explicit engine: each platform source set supplies exactly one Ktor engine on its
    // classpath (OkHttp on the JVM/Android app, the JS fetch/XHR engine on web), which Ktor picks
    // up automatically, keeping this service fully multiplatform.
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets)
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
            bearerAuth(apiKey)
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
            bearerAuth(apiKey)
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
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

        val content = response.choices.firstOrNull()?.message?.content
            ?: error("Empty response from Qwen")

        return json.decodeFromString(MissionReply.serializer(), content)
    }

    /**
     * Synthesizes [text] to speech using Alibaba's CosyVoice model and returns the raw audio bytes
     * (a WAV file) so the caller can play it back.
     *
     * CosyVoice is only reachable over a WebSocket ([TTS_ENDPOINT]). The previous Qwen3-TTS
     * integration called DashScope's HTTP `multimodal-generation` endpoint directly, which has no
     * CORS support: a browser sends a CORS preflight `OPTIONS` request first, which carries no
     * `Authorization` header (browsers never send the real header on a preflight), and DashScope's
     * HTTP endpoint rejects that with a 401 before the real POST is ever attempted. A WebSocket
     * handshake has no such preflight, so the exact same call now works from both the Android app
     * and the web build.
     *
     * The flow follows CosyVoice's WebSocket protocol: connect, send a `run-task` command
     * describing the voice/format, wait for `task-started`, stream [text] via a `continue-task`
     * command, then send `finish-task`; the server streams back one or more binary audio frames
     * (concatenated here) and finally a `task-finished` event. CosyVoice detects the spoken
     * language directly from [text] (it supports 31 languages out of the box), so no per-language
     * allow-list is consulted or maintained here. [language] selects the voice: if the learner has
     * an enabled cloned voice for it, that voice speaks; otherwise the shared default voice-design
     * voice is used (see [resolveVoiceId]).
     *
     * Throws if the connection/handshake fails or the server reports a `task-failed` event.
     */
    suspend fun synthesizeSpeech(text: String, language: Language): ByteArray {
        val voiceId = resolveVoiceId(language)
        val taskId = randomTaskId()
        val runTask = RunTaskMessage(
            header = TaskHeader(action = "run-task", taskId = taskId),
            payload = RunTaskPayload(
                model = TTS_MODEL,
                parameters = TtsParameters(voice = voiceId),
            ),
        )
        val continueTask = ContinueTaskMessage(
            header = TaskHeader(action = "continue-task", taskId = taskId),
            payload = ContinueTaskPayload(input = ContinueTaskInput(text = text)),
        )
        val finishTask = FinishTaskMessage(header = TaskHeader(action = "finish-task", taskId = taskId))

        val audioChunks = mutableListOf<ByteArray>()
        var failure: String? = null
        var finished = false

        try {
            client.webSocket(TTS_ENDPOINT, request = {
                bearerAuth(apiKey)
            }) {
                send(Frame.Text(json.encodeToString(RunTaskMessage.serializer(), runTask)))
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> audioChunks.add(frame.data)
                        is Frame.Text -> {
                            val header = runCatching {
                                json.decodeFromString(TaskEvent.serializer(), frame.readText())
                            }.getOrNull()?.header ?: continue
                            when (header.event) {
                                "task-started" -> {
                                    send(Frame.Text(json.encodeToString(ContinueTaskMessage.serializer(), continueTask)))
                                    send(Frame.Text(json.encodeToString(FinishTaskMessage.serializer(), finishTask)))
                                }
                                "task-finished" -> {
                                    finished = true
                                    return@webSocket
                                }
                                "task-failed" -> {
                                    failure = "${header.errorCode}: ${header.errorMessage}"
                                    return@webSocket
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                // The channel ended without a task-finished/task-failed event, e.g. the server can
                // drop the connection outright on a permission error (such as "Model access denied"
                // when [TTS_MODEL] isn't enabled for [QWEN_API_KEY]'s Model Studio workspace) instead
                // of sending a graceful task-failed event. Surface the real close reason rather than
                // silently falling through to a generic "no audio" error below.
                if (!finished) {
                    failure = closeReason.await()?.message ?: "connection closed unexpectedly"
                }
            }
        } catch (e: Throwable) {
            // Surface the real reason (e.g. a 401/403 from an invalid API key at the handshake, or a
            // dropped connection) instead of a bare exception with no context.
            throw IllegalStateException("CosyVoice TTS request failed: ${e.message}", e)
        }

        failure?.let { error("CosyVoice TTS request failed: $it") }
        if (audioChunks.isEmpty()) error("CosyVoice TTS returned no audio")

        val audio = ByteArray(audioChunks.sumOf { it.size })
        var offset = 0
        for (chunk in audioChunks) {
            chunk.copyInto(audio, offset)
            offset += chunk.size
        }
        return audio
    }

    /** A random UUID-formatted task id, as CosyVoice's `run-task`/`continue-task`/`finish-task` protocol requires. */
    private fun randomTaskId(): String {
        val hex = "0123456789abcdef"
        fun segment(length: Int) = buildString { repeat(length) { append(hex[Random.nextInt(16)]) } }
        return "${segment(8)}-${segment(4)}-${segment(4)}-${segment(4)}-${segment(12)}"
    }

    /**
     * Returns the `voice_id` to pass as [TtsParameters.voice] in [synthesizeSpeech].
     *
     * [TTS_MODEL] (`cosyvoice-v3.5-flash`) ships with no built-in system voices at all — unlike
     * earlier CosyVoice versions, DashScope rejects every preset voice name for it with an
     * `InvalidParameter` error. Per Alibaba's docs, a voice must first be generated once via the
     * Voice Design API (a text description → a synthetic voice, no recording needed) and its
     * `voice_id` reused for every subsequent synthesis call against the SAME [TTS_MODEL] (a
     * `voice_id` is bound to the `target_model` it was created with).
     *
     * That one-time creation is now done OFFLINE, not at app runtime: [TTS_VOICE_DESIGN_ENDPOINT]
     * (like every other DashScope HTTP endpoint used by this service, see [TTS_ENDPOINT]) has zero
     * CORS support, so a browser's preflight `OPTIONS` request to it is rejected with 401 before the
     * real `POST` is ever attempted — exactly the 401 reported from the web build. Calling it from
     * [synthesizeSpeech] on every fresh install therefore can never work in a browser, no matter the
     * API key or permissions. [TTS_DEFAULT_VOICE_ID] is the `voice_id` of a real voice generated once
     * this way (verified end-to-end against the live endpoint) and simply reused by every install and
     * platform from then on, so no runtime call to that endpoint is needed at all for the default
     * voice. [cachedVoiceId] is only consulted for backward compatibility with installs that had
     * already persisted a voice_id from an earlier version of this method.
     *
     * A learner's own cloned voice takes precedence: if [enabledClonedVoiceId] returns an enrolled,
     * enabled voice for [language], it's used directly and the default voice is skipped entirely.
     */
    private fun resolveVoiceId(language: Language): String {
        enabledClonedVoiceId(language)?.takeIf { it.isNotBlank() }?.let { return it }
        return inMemoryVoiceId ?: (cachedVoiceId() ?: TTS_DEFAULT_VOICE_ID).also { inMemoryVoiceId = it }
    }

    /**
     * Registers a learner-provided audio sample as a cloned CosyVoice voice via DashScope's
     * `voice-enrollment`/`create_voice` action, binding it to [TTS_MODEL] so [synthesizeSpeech] can
     * drive it. [audio] is the raw recorded/uploaded sample and is delivered inline as a base64
     * `data:` URI in the request's `url` field, so no separate upload/hosting step is needed;
     * [mimeType] describes that sample (WAV for the app's own recordings). [name] is only used to
     * derive the DashScope enrollment [prefix]; the spoken language is detected from the sample
     * itself, so it isn't sent here (the caller files the returned voice under the right language).
     *
     * Returns the new CosyVoice `voice_id`. Throws [IllegalStateException] carrying DashScope's
     * actual code/message when enrollment fails, so the UI can reveal the real reason.
     */
    suspend fun enrollClonedVoice(
        name: String,
        audio: ByteArray,
        mimeType: String = "audio/wav",
    ): String {
        if (audio.isEmpty()) error("No audio was provided to clone")
        val dataUri = "data:$mimeType;base64,${audio.encodeBase64()}"
        val request = EnrollVoiceRequest(
            input = EnrollVoiceInput(
                targetModel = TTS_MODEL,
                prefix = enrollmentPrefix(name),
                url = dataUri,
            ),
        )
        val response: CreateVoiceResponse = try {
            client.post(TTS_VOICE_DESIGN_ENDPOINT) {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        } catch (e: ResponseException) {
            val body = runCatching { e.response.bodyAsText() }.getOrNull()
            throw IllegalStateException(
                "Voice cloning request failed (${e.response.status}): ${body ?: e.message}",
                e,
            )
        } catch (e: Throwable) {
            throw IllegalStateException("Voice cloning request failed: ${e.message}", e)
        }

        return response.output?.voiceId
            ?: error("Voice cloning returned no voice id (code=${response.code}, message=${response.message})")
    }

    /**
     * A DashScope-safe enrollment prefix derived from [name]: lowercase ASCII letters/digits only,
     * capped at 10 characters and never empty. DashScope uses it to namespace the generated voice
     * id, so non-Latin names fall back to a fixed literal.
     */
    private fun enrollmentPrefix(name: String): String =
        name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(10).ifBlank { "voice" }

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
        const val QWEN_API_KEY = "sk-ws-H.EMXXXLH.PM2V.MEUCIQCImzN24a8LdyNrnJ2cBLTwFnG-V6EU9hTvQEZNXg_PUgIgXxcdGiXJqrxukUizL7Km6EQ52wUdB9SduFDWksiul1Y"

        /**
         * The DashScope-compatible endpoint host issued with [QWEN_API_KEY]. This is the single
         * source of truth for every Qwen endpoint (chat completions, TTS, and realtime ASR); the
         * Android [com.queatz.microcosmicdisambiguation.audio.QwenSpeechRecognizer] and web
         * `WebSpeechRecognizer` reference [REALTIME_ENDPOINT] below rather than hard-coding the
         * host themselves.
         */
        private const val HOST = "ws-hrt7qnf0x5y5avkq.cn-beijing.maas.aliyuncs.com"

        private const val ENDPOINT = "https://$HOST/compatible-mode/v1/chat/completions"
        private const val MODEL = "qwen-plus"

        /**
         * The task-based WebSocket endpoint used for CosyVoice TTS and FunASR realtime speech recognition.
         */
        const val INFERENCE_ENDPOINT = "wss://$HOST/api-ws/v1/inference"

        /**
         * CosyVoice's real-time speech synthesis WebSocket endpoint. Unlike the old Qwen3-TTS
         * `multimodal-generation` HTTP endpoint, this has no CORS preflight to fail, which is what
         * fixes the 401 seen from the browser build (see [synthesizeSpeech]).
         */
        private const val TTS_ENDPOINT = INFERENCE_ENDPOINT

        /**
         * The CosyVoice model used for cloud speech synthesis over [TTS_ENDPOINT]. As with
         * [REALTIME_ASR_MODEL], this model must be enabled for [QWEN_API_KEY] in its Model Studio
         * workspace; if it isn't, the `continue-task` step fails with a `task-failed` event whose
         * `error_code`/`error_message` (e.g. "Model access denied") is now surfaced by
         * [synthesizeSpeech] instead of swallowed.
         *
         * `cosyvoice-v3.5-flash` (only available in the China (Beijing) region, which matches
         * [HOST]) ships with NO built-in system voices at all — passing any preset voice name (the
         * older `-v3`/`-v2`/`-v1` series' approach) is rejected outright with `InvalidParameter`.
         * A voice must instead be generated once via the Voice Design API and its `voice_id` reused
         * for every call; see [resolveVoiceId].
         */
        private const val TTS_MODEL = "cosyvoice-v3.5-flash"

        /**
         * DashScope's Voice Design/Cloning HTTP endpoint. Only [enrollClonedVoice] still calls this
         * at runtime (a learner explicitly cloning their own voice); the shared default voice no
         * longer does (see [resolveVoiceId]/[TTS_DEFAULT_VOICE_ID]).
         *
         * Like every DashScope HTTP endpoint used by this service, it has zero CORS support: a
         * browser's `OPTIONS` preflight to it is rejected with 401 (verified live) before the real
         * `POST` is ever attempted, so [enrollClonedVoice] can only ever succeed from a native HTTP
         * client (the Android app), never from the web build, regardless of API key permissions.
         */
        private const val TTS_VOICE_DESIGN_ENDPOINT = "https://$HOST/api/v1/services/audio/tts/customization"

        /**
         * The `voice_id` of the single default voice every install/platform speaks with unless a
         * learner has an enabled cloned voice (see [resolveVoiceId]). Generated once, offline,
         * against [TTS_VOICE_DESIGN_ENDPOINT] from the voice description "A warm, friendly, and
         * cheerful young adult voice with clear articulation, a natural speaking pace, and an
         * upbeat, encouraging tone, suitable for a language-learning app.", bound to [TTS_MODEL],
         * and verified end-to-end (synthesis over [TTS_ENDPOINT] with this id produces valid audio).
         * Voice descriptions only support Chinese/English text, but per Alibaba's docs the
         * resulting voice can still synthesize speech in any of CosyVoice's 31 supported languages,
         * so this one voice covers every [Language].
         */
        private const val TTS_DEFAULT_VOICE_ID = "cosyvoice-v3.5-flash-vd-learner-92357aee58e549c2904d945ce64020cc"

        /**
         * The realtime ASR WebSocket endpoint (OpenAI-style realtime protocol). Shared by the
         * Android and web speech recognizers so the host lives in exactly one place.
         */
        const val REALTIME_ENDPOINT = "wss://$HOST/api-ws/v1/realtime"

        /**
         * The realtime ASR model used over [REALTIME_ENDPOINT]. This is the single source of truth
         * for the model name; the Android [com.queatz.microcosmicdisambiguation.audio.QwenSpeechRecognizer]
         * and web `WebSpeechRecognizer` reference this constant rather than hard-coding it
         * themselves.
         *
         * `fun-asr-realtime` is the realtime ASR model deployed on this endpoint. This model must be
         * enabled for [QWEN_API_KEY] in the Model Studio workspace, otherwise the session opens but
         * the server closes it with "Model access denied".
         */
        const val REALTIME_ASR_MODEL = "fun-asr-realtime"

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

// DashScope's Voice Design/Cloning HTTP response schema (`create_voice`). See
// [QwenService.enrollClonedVoice]; [TTS_DEFAULT_VOICE_ID] was generated offline against this same
// schema, so no request data class is needed for it at runtime.

@Serializable
private data class CreateVoiceResponse(
    val output: CreateVoiceOutput? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
private data class CreateVoiceOutput(
    // The CosyVoice `voice-enrollment`/`create_voice` action returns the new id under `voice_id`
    // (verified against the live Beijing-region endpoint); the Qwen `qwen-voice-design`/`create`
    // variant instead returns `voice`, but this service only ever uses the CosyVoice path.
    @SerialName("voice_id") val voiceId: String? = null,
)

// Voice cloning re-uses the same `voice-enrollment`/`create_voice` action as voice design, but
// supplies a recorded/uploaded audio sample (`url`) instead of a text `voice_prompt`. See
// [QwenService.enrollClonedVoice]; the response reuses [CreateVoiceResponse].

@Serializable
private data class EnrollVoiceRequest(
    val model: String = "voice-enrollment",
    val input: EnrollVoiceInput,
)

@Serializable
private data class EnrollVoiceInput(
    val action: String = "create_voice",
    @SerialName("target_model") val targetModel: String,
    val prefix: String,
    /** The audio sample to clone, delivered inline as a base64 `data:` URI. */
    val url: String,
)

// CosyVoice's WebSocket client/server message schema (run-task/continue-task/finish-task,
// task-started/task-finished/task-failed). See [QwenService.synthesizeSpeech].

@Serializable
private data class TaskHeader(
    val action: String,
    @SerialName("task_id") val taskId: String,
    val streaming: String = "duplex",
)

@Serializable
private class EmptyPayload

@Serializable
private data class RunTaskMessage(
    val header: TaskHeader,
    val payload: RunTaskPayload,
)

@Serializable
private data class RunTaskPayload(
    @SerialName("task_group") val taskGroup: String = "audio",
    val task: String = "tts",
    val function: String = "SpeechSynthesizer",
    val model: String,
    val parameters: TtsParameters,
    val input: EmptyPayload = EmptyPayload(),
)

@Serializable
private data class TtsParameters(
    @SerialName("text_type") val textType: String = "PlainText",
    val voice: String,
    val format: String = "wav",
    @SerialName("sample_rate") val sampleRate: Int = 24_000,
)

@Serializable
private data class ContinueTaskMessage(
    val header: TaskHeader,
    val payload: ContinueTaskPayload,
)

@Serializable
private data class ContinueTaskPayload(
    val input: ContinueTaskInput,
)

@Serializable
private data class ContinueTaskInput(
    val text: String,
)

@Serializable
private data class FinishTaskMessage(
    val header: TaskHeader,
    val payload: FinishTaskPayload = FinishTaskPayload(),
)

@Serializable
private data class FinishTaskPayload(
    val input: EmptyPayload = EmptyPayload(),
)

/** A server event (`task-started`/`result-generated`/`task-finished`/`task-failed`); only the header is needed. */
@Serializable
private data class TaskEvent(
    val header: TaskEventHeader? = null,
)

@Serializable
private data class TaskEventHeader(
    val event: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * A minimal, dependency-free standard Base64 encoder used to inline an audio sample as a `data:`
 * URI for voice enrollment, so the same code path works on both Android and the web without a
 * platform-specific encoder.
 */
private fun ByteArray.encodeBase64(): String {
    val sb = StringBuilder((size + 2) / 3 * 4)
    var i = 0
    while (i + 3 <= size) {
        val n = ((this[i].toInt() and 0xFF) shl 16) or
            ((this[i + 1].toInt() and 0xFF) shl 8) or
            (this[i + 2].toInt() and 0xFF)
        sb.append(BASE64_ALPHABET[(n ushr 18) and 0x3F])
        sb.append(BASE64_ALPHABET[(n ushr 12) and 0x3F])
        sb.append(BASE64_ALPHABET[(n ushr 6) and 0x3F])
        sb.append(BASE64_ALPHABET[n and 0x3F])
        i += 3
    }
    when (size - i) {
        1 -> {
            val n = (this[i].toInt() and 0xFF) shl 16
            sb.append(BASE64_ALPHABET[(n ushr 18) and 0x3F])
            sb.append(BASE64_ALPHABET[(n ushr 12) and 0x3F])
            sb.append("==")
        }
        2 -> {
            val n = ((this[i].toInt() and 0xFF) shl 16) or ((this[i + 1].toInt() and 0xFF) shl 8)
            sb.append(BASE64_ALPHABET[(n ushr 18) and 0x3F])
            sb.append(BASE64_ALPHABET[(n ushr 12) and 0x3F])
            sb.append(BASE64_ALPHABET[(n ushr 6) and 0x3F])
            sb.append("=")
        }
    }
    return sb.toString()
}
