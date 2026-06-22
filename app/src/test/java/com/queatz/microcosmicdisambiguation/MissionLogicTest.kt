package com.queatz.microcosmicdisambiguation

import com.queatz.microcosmicdisambiguation.data.Mission
import com.queatz.microcosmicdisambiguation.data.MissionChoice
import com.queatz.microcosmicdisambiguation.data.MissionReply
import com.queatz.microcosmicdisambiguation.data.MissionSet
import com.queatz.microcosmicdisambiguation.data.MissionTurn
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionLogicTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun missionSet_jsonRoundTrips() {
        val missions = MissionSet(
            missions = listOf(
                Mission(
                    title = "At the fruit stand",
                    icon = "🍌",
                    setting = "You walk up to a fruit vendor.",
                    role = "a fruit vendor",
                    turns = listOf(
                        MissionTurn(
                            speaker = "你好，要买什么？",
                            speakerPinyin = "nǐ hǎo, yào mǎi shénme?",
                            speakerEnglish = "Hello, what would you like to buy?",
                            emoji = "🧑\u200d🌾",
                            prompt = "Tell them you want a banana.",
                            choices = listOf(
                                MissionChoice(
                                    hanzi = "我想买香蕉。",
                                    pinyin = "wǒ xiǎng mǎi xiāngjiāo.",
                                    english = "I want to buy a banana.",
                                    correct = true,
                                    reply = "Great! Here's your banana.",
                                ),
                                MissionChoice(
                                    hanzi = "再见。",
                                    pinyin = "zàijiàn.",
                                    english = "Goodbye.",
                                    correct = false,
                                    reply = "Oh, leaving already? That wasn't what you wanted to say.",
                                ),
                            ),
                        ),
                    ),
                    success = "You bought a banana all by yourself!",
                ),
            ),
        )

        val decoded = json.decodeFromString(
            MissionSet.serializer(),
            json.encodeToString(MissionSet.serializer(), missions),
        )

        assertEquals(missions, decoded)
        assertTrue(decoded.missions.first().turns.first().choices.first().correct)
    }

    @Test
    fun missionSet_parsesFromModelJson() {
        val content = """
            {
              "missions": [
                {
                  "title": "Ordering tea",
                  "icon": "🍵",
                  "setting": "You're at a tea shop counter.",
                  "role": "a barista",
                  "turns": [
                    {
                      "speaker": "您要点什么？",
                      "speakerRomanization": "nín yào diǎn shénme?",
                      "speakerTranslation": "What would you like to order?",
                      "emoji": "🧑‍🍳",
                      "prompt": "Order a cup of tea.",
                      "choices": [
                        { "text": "我要一杯茶。", "romanization": "wǒ yào yì bēi chá.", "translation": "I'd like a cup of tea.", "correct": true, "reply": "Coming right up!" },
                        { "text": "厕所在哪里？", "romanization": "cèsuǒ zài nǎlǐ?", "translation": "Where's the toilet?", "correct": false, "reply": "That's not an order — they look confused." },
                        { "text": "多少钱？", "romanization": "duōshǎo qián?", "translation": "How much?", "correct": false, "reply": "They haven't told you the price yet." }
                      ]
                    }
                  ],
                  "success": "You ordered tea like a regular!"
                }
              ]
            }
        """.trimIndent()

        val set = json.decodeFromString(MissionSet.serializer(), content)
        val mission = set.missions.single()

        assertEquals("Ordering tea", mission.title)
        assertEquals(1, mission.turns.size)
        val choices = mission.turns.first().choices
        assertEquals(3, choices.size)
        assertEquals(1, choices.count { it.correct })
        assertEquals("我要一杯茶。", choices.first { it.correct }.hanzi)
    }

    @Test
    fun missionSet_toleratesMissionsAsObjectMap() {
        // The model sometimes emits a list as an index-keyed object; it must not crash.
        val content = """
            {
              "missions": {
                "1": { "title": "A", "turns": [] },
                "2": { "title": "B", "turns": [] }
              }
            }
        """.trimIndent()

        val set = json.decodeFromString(MissionSet.serializer(), content)
        assertEquals(listOf("A", "B"), set.missions.map { it.title })
    }

    @Test
    fun missionSet_toleratesChoicesAsObjectMap() {
        val content = """
            {
              "missions": [
                {
                  "title": "X",
                  "turns": [
                    {
                      "speaker": "你好",
                      "choices": {
                        "a": { "text": "你好", "correct": true },
                        "b": { "text": "再见", "correct": false }
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val set = json.decodeFromString(MissionSet.serializer(), content)
        val choices = set.missions.single().turns.single().choices
        assertEquals(2, choices.size)
        assertTrue(choices[0].correct)
        assertFalse(choices[1].correct)
    }

    @Test
    fun missionSet_toleratesBareStringsInChoices() {
        // Bare (non-object) entries are dropped rather than crashing the parse.
        val content = """
            {
              "missions": [
                {
                  "title": "X",
                  "turns": [
                    { "speaker": "你好", "choices": ["oops", { "text": "你好", "correct": true }] }
                  ]
                }
              ]
            }
        """.trimIndent()

        val set = json.decodeFromString(MissionSet.serializer(), content)
        val choices = set.missions.single().turns.single().choices
        assertEquals(1, choices.size)
        assertEquals("你好", choices.single().hanzi)
    }

    @Test
    fun missionReply_parsesFromModelJson() {
        val content = """
            {
              "translation": "I'd like to buy two bananas, please.",
              "reply": "好的，两根香蕉，一共五块钱。",
              "replyRomanization": "hǎo de, liǎng gēn xiāngjiāo, yígòng wǔ kuài qián.",
              "replyTranslation": "Sure, two bananas, five yuan in total.",
              "onTopic": true
            }
        """.trimIndent()

        val reply = json.decodeFromString(MissionReply.serializer(), content)

        assertEquals("I'd like to buy two bananas, please.", reply.translation)
        assertEquals("好的，两根香蕉，一共五块钱。", reply.reply)
        assertEquals("Sure, two bananas, five yuan in total.", reply.replyTranslation)
        assertTrue(reply.onTopic)
    }

    @Test
    fun missionReply_defaultsAreSafeWhenFieldsMissing() {
        // The model may omit fields (e.g. romanization for Latin-script languages); parsing must not
        // crash and missing fields fall back to safe defaults.
        val reply = json.decodeFromString(MissionReply.serializer(), """{ "reply": "Hola" }""")

        assertEquals("Hola", reply.reply)
        assertEquals("", reply.translation)
        assertEquals("", reply.replyRomanization)
        assertTrue(reply.onTopic)
    }
}
