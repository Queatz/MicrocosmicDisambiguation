package com.queatz.microcosmicdisambiguation.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.queatz.microcosmicdisambiguation.R
import com.queatz.microcosmicdisambiguation.audio.TtsEngine
import com.queatz.microcosmicdisambiguation.data.Alignment as AlignmentChunk
import com.queatz.microcosmicdisambiguation.data.alignedEnglishRange
import com.queatz.microcosmicdisambiguation.data.BLANK
import com.queatz.microcosmicdisambiguation.data.Language
import com.queatz.microcosmicdisambiguation.data.SentenceStructure
import com.queatz.microcosmicdisambiguation.data.StudyGuide
import com.queatz.microcosmicdisambiguation.data.StudyWord
import com.queatz.microcosmicdisambiguation.data.WordBreakdown
import com.queatz.microcosmicdisambiguation.data.assembleSentence
import com.queatz.microcosmicdisambiguation.data.speakableTokens
import kotlin.math.roundToInt

@Composable
fun StudyScreen(
    guide: StudyGuide,
    speaker: TtsEngine,
    language: Language,
    onClose: () -> Unit,
    onStartMissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (guide.structures.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.study_no_content))
        }
        return
    }

    var page by remember { mutableIntStateOf(0) }
    val structure = guide.structures[page]
    val isLast = page == guide.structures.lastIndex

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // key by page so the blanks reset for each sentence structure. The back button and title
        // live inside the page so they scroll together with the rest of the content.
        SentencePage(
            title = guide.title,
            onClose = onClose,
            structure = structure,
            speaker = speaker,
            language = language,
            modifier = Modifier.weight(1f),
        )

        // On the final sentence, invite the learner to put it into practice with the missions —
        // simulated real-life situations they navigate using what they just studied.
        if (isLast) {
            Button(
                onClick = onStartMissions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.study_try_mission))
            }
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.close))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onStartMissions,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.study_missions))
                }
                Button(
                    onClick = { page++ },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.next))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun SentencePage(
    title: String,
    onClose: () -> Unit,
    structure: SentenceStructure,
    speaker: TtsEngine,
    language: Language,
    modifier: Modifier = Modifier,
) {
    val segments = remember(structure.template) { structure.template.split(BLANK) }
    val blankCount = (segments.size - 1).coerceAtLeast(0)

    // one nullable slot per blank
    val slots = remember(structure) {
        mutableStateListOf<StudyWord?>().apply { repeat(blankCount) { add(null) } }
    }

    // words still available in the word bank (not yet placed in a blank). Dragging a word out of
    // the bank removes it here so the remaining chips reflow to fill its space; returning a word
    // (by dropping it outside a blank or replacing it) re-adds it.
    val availableWords = remember(structure) {
        mutableStateListOf<StudyWord>().apply { addAll(structure.words) }
    }

    // shared drag state for this page
    val blankBounds = remember(structure) { mutableMapOf<Int, Rect>() }
    var dragging by remember { mutableStateOf<StudyWord?>(null) }
    var dragSourceBlank by remember { mutableStateOf<Int?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    // window-space position of the drag container, used to place the floating preview
    var containerWindowOffset by remember { mutableStateOf(Offset.Zero) }
    var previewWidth by remember { mutableStateOf(0) }
    var previewHeight by remember { mutableStateOf(0) }

    // Scroll state for the page, plus a nested-scroll connection that freezes scrolling while a
    // word is being dragged so the drag gesture can never make the page scroll out from under the
    // learner. It consumes all pre-scroll deltas (without removing the scroll modifier, so the
    // scroll position never jumps) whenever a drag is active.
    val scrollState = rememberScrollState()
    val dragScrollLock = remember {
        object : NestedScrollConnection {
            var locked = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (locked) available else Offset.Zero
        }
    }
    dragScrollLock.locked = dragging != null

    // the [start, end) character range of the assembled sentence currently being spoken
    var speakingRange by remember(structure) { mutableStateOf<IntRange?>(null) }
    // index of the word currently focused by the step-through "word by word" player, or null when
    // that mode is inactive
    var wordByWordIndex by remember(structure) { mutableStateOf<Int?>(null) }
    // the sentence or word the learner is currently practicing pronouncing, or null when the
    // pronunciation-practice modal is closed
    var practiceTarget by remember(structure) { mutableStateOf<String?>(null) }

    fun assembledSentence(): String =
        assembleSentence(structure.template, slots.map { it?.hanzi })

    fun assembledEnglish(): String =
        assembleSentence(structure.english, slots.map { it?.english })

    // The complete, intended sentence with every blank already filled in. Used to play the
    // original sentence out loud when the learner arrives on this screen.
    fun originalSentence(): String = when {
        structure.tokens.isNotEmpty() -> structure.tokens.joinToString("")
        structure.breakdown.isNotEmpty() -> structure.breakdown.joinToString("") { it.hanzi }
        else -> assembledSentence()
    }

    // Words the model marked as punctuation, so the word-by-word player can skip over them.
    val punctuationTokens = remember(structure) {
        buildSet {
            structure.breakdown.filter { it.punctuation }.forEach { add(it.hanzi) }
            structure.alignment.filter { it.punctuation }.forEach { add(it.zh) }
        }
    }

    // Tokenizes the currently assembled sentence into individual words, preferring the back-end
    // provided tokens so the word-by-word player highlights real words instead of guessing.
    // Punctuation marks and not-yet-filled blanks are dropped so the player steps only through
    // real, speakable words (and never counts a blank placeholder as a word).
    fun assembledTokens() = speakableTokens(
        assembledSentence(),
        buildList {
            add(BLANK)
            addAll(structure.tokens)
            structure.words.forEach { add(it.hanzi) }
            structure.breakdown.forEach { add(it.hanzi) }
        },
        punctuationTokens,
    )

    // Speaks the assembled sentence as a whole and highlights words/characters as they are read.
    fun speakSentence() {
        wordByWordIndex = null
        speaker.speak(
            text = assembledSentence(),
            onStart = { speakingRange = null },
            onRange = { start, end -> speakingRange = start until end },
            onDone = { speakingRange = null },
        )
    }

    // Plays the complete original sentence out loud (without per-word highlighting, since its
    // characters do not line up with the blanks shown on screen).
    fun speakOriginal() {
        wordByWordIndex = null
        speakingRange = null
        speaker.speak(text = originalSentence())
    }

    // Plays a single word of the assembled sentence and highlights exactly that word. Advancing
    // past the final word exits word-by-word mode. This is a second way to play the sentence that
    // complements [speakSentence].
    fun playWord(index: Int) {
        val tokens = assembledTokens()
        if (index !in tokens.indices) {
            wordByWordIndex = null
            speakingRange = null
            return
        }
        val token = tokens[index]
        wordByWordIndex = index
        speakingRange = token.range
        if (token.text != BLANK && token.text.isNotBlank()) {
            speaker.speak(token.text)
        }
    }

    fun finishDrag() {
        val word = dragging
        if (word != null) {
            val target = blankBounds.entries.firstOrNull { it.value.contains(dragPosition) }?.key
            if (target != null) {
                // displace whatever already occupied the target back into the word bank
                slots[target]?.let { availableWords.add(it) }
                slots[target] = word
                // a word dragged out of the bank is now in a blank, so it leaves the bank
                availableWords.remove(word)
            } else {
                // dropped outside any blank -> the whole cell belongs in the word bank
                // (a bank-origin word is still in the bank, so only add it if missing)
                if (word !in availableWords) {
                    availableWords.add(word)
                }
            }
            speakSentence()
        }
        dragging = null
        dragSourceBlank = null
    }

    // Play the original sentence whenever the learner navigates to a new sentence structure.
    LaunchedEffect(structure) {
        speakOriginal()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                containerWindowOffset = Offset(bounds.left, bounds.top)
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(dragScrollLock)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Back button to the left of the title; both scroll with the rest of the page.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Sentence structure with blanks. Tapping it plays it out loud.
            Card(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .pointerInput(structure) {
                        detectTapGestures { speakSentence() }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val assembled = assembledSentence()
                    val (segmentRanges, blankRanges) = sentenceUnitRanges(segments, slots, blankCount)
                    val highlightColor = MaterialTheme.colorScheme.primary

                    // LookaheadScope lets the words and blanks animate to their new positions
                    // (instead of jumping) whenever a blank is filled, emptied, or resized.
                    LookaheadScope {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            segments.forEachIndexed { index, segment ->
                                if (segment.isNotEmpty()) {
                                    Text(
                                        text = highlightedSegment(
                                            segment = segment,
                                            segmentStart = segmentRanges[index].first,
                                            speakingRange = speakingRange,
                                            highlightColor = highlightColor,
                                        ),
                                        style = MaterialTheme.typography.headlineSmall,
                                        modifier = Modifier.animateBounds(this@LookaheadScope),
                                    )
                                }
                                if (index < blankCount) {
                                    BlankSlot(
                                        word = slots[index],
                                        highlighted = speakingRange overlaps blankRanges[index],
                                        onBoundsChanged = { blankBounds[index] = it },
                                        onDragStart = { startWindow ->
                                            val w = slots[index]
                                            if (w != null) {
                                                dragging = w
                                                dragSourceBlank = index
                                                // lift the word out so the blank empties while dragging
                                                slots[index] = null
                                                dragPosition = startWindow
                                            }
                                        },
                                        onDrag = { dragPosition += it },
                                        onDragEnd = { finishDrag() },
                                        modifier = Modifier.animateBounds(this@LookaheadScope),
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = highlightedEnglish(
                            english = assembledEnglish(),
                            assembledChinese = assembled,
                            alignment = structure.alignment,
                            speakingRange = speakingRange,
                            spokenLength = assembled.length,
                            highlightColor = highlightColor,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Two ways to play the sentence: all at once, or one word at a time.
            PlayControls(
                wordByWordIndex = wordByWordIndex,
                wordCount = assembledTokens().size,
                onPlaySentence = { speakSentence() },
                onStartWordByWord = { playWord(0) },
                onNextWord = { playWord((wordByWordIndex ?: -1) + 1) },
                onStopWordByWord = {
                    wordByWordIndex = null
                    speakingRange = null
                },
                // Drop any blanks the learner hasn't filled in yet so they only practice the
                // words actually placed into the sentence. Removing a blank can leave a double
                // space (or a leading/trailing one) behind, so collapse and trim those too.
                onPractice = {
                    practiceTarget = assembledSentence()
                        .replace(BLANK, "")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                },
            )

            Text(
                text = stringResource(R.string.study_words),
                style = MaterialTheme.typography.titleMedium,
            )
            // LookaheadScope lets the remaining chips animate smoothly into place when a word is
            // dragged out of (or dropped back into) the bank, instead of snapping.
            LookaheadScope {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableWords.forEach { word ->
                        WordChip(
                            word = word,
                            onTap = { speaker.speak(word.hanzi) },
                            // keep the chip in the bank (its gesture node must survive) but
                            // collapse it to 0 size so the remaining chips reflow to fill the gap
                            hidden = dragging == word && dragSourceBlank == null,
                            onDragStart = { startWindow ->
                                dragging = word
                                dragSourceBlank = null
                                dragPosition = startWindow
                            },
                            onDrag = { dragPosition += it },
                            onDragEnd = { finishDrag() },
                            modifier = Modifier.animateBounds(this@LookaheadScope),
                        )
                    }
                }
            }

            // Detailed, word-by-word breakdown of the sentence. Tap any word to hear it.
            // Punctuation-only entries are skipped so only real words get a row.
            if (structure.breakdown.any { !it.punctuation }) {
                Text(
                    text = stringResource(R.string.study_breakdown),
                    style = MaterialTheme.typography.titleMedium,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    structure.breakdown.filterNot { it.punctuation }.forEach { entry ->
                        BreakdownRow(
                            entry = entry,
                            onTap = { speaker.speak(entry.hanzi) },
                            onPractice = { practiceTarget = entry.hanzi },
                        )
                    }
                }
            }
        }

        // Floating preview of the word being dragged.
        dragging?.let { word ->
            Box(
                modifier = Modifier
                    .zIndex(1f)
                    .offset {
                        // dragPosition is in window coordinates; translate it into this Box's
                        // local space and center the preview on the pointer
                        val local = dragPosition - containerWindowOffset
                        IntOffset(
                            local.x.roundToInt() - previewWidth / 2,
                            local.y.roundToInt() - previewHeight / 2,
                        )
                    }
                    .onGloballyPositioned {
                        previewWidth = it.size.width
                        previewHeight = it.size.height
                    }
                    // The alpha above forces an offscreen layer sized to this Box. Without this
                    // padding the Card's rounded elevation shadow would be clipped to the layer's
                    // rectangular bounds, showing a square shadow around the rounded chip.
                    .padding(12.dp),
            ) {
                WordChipContent(word = word, elevated = true)
            }
        }

        // The pronunciation-practice modal, opened from the sentence's play controls or any word
        // in the breakdown.
        practiceTarget?.let { target ->
            PronunciationPracticeDialog(
                target = target,
                language = language,
                speaker = speaker,
                onDismiss = { practiceTarget = null },
            )
        }
    }
}

/**
 * Buttons offering the two ways to play the sentence: [onPlaySentence] reads the whole thing at
 * once, while the word-by-word player ([onStartWordByWord] / [onNextWord]) steps through one word
 * at a time so each spoken word can be highlighted precisely.
 */
@Composable
private fun PlayControls(
    wordByWordIndex: Int?,
    wordCount: Int,
    onPlaySentence: () -> Unit,
    onStartWordByWord: () -> Unit,
    onNextWord: () -> Unit,
    onStopWordByWord: () -> Unit,
    onPractice: () -> Unit,
) {
    if (wordByWordIndex == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onPlaySentence, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.study_play_sentence))
            }
            OutlinedButton(
                onClick = onStartWordByWord,
                enabled = wordCount > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.study_word_by_word))
            }
            // Speak the sentence yourself and get instant pronunciation feedback.
            IconButton(onClick = onPractice) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = stringResource(R.string.study_pronounce),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    } else {
        val isLastWord = wordByWordIndex >= wordCount - 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onStopWordByWord, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.stop))
                }
                Button(onClick = onNextWord, modifier = Modifier.weight(1f)) {
                    Text(if (isLastWord) stringResource(R.string.finish) else stringResource(R.string.study_next_word))
                }
            }
        }
    }
}

@Composable
private fun BlankSlot(
    word: StudyWord?,
    highlighted: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var windowOffset by remember { mutableStateOf(Offset.Zero) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            highlighted -> MaterialTheme.colorScheme.tertiaryContainer
            word == null -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        modifier = modifier
            .width(if (word == null) 72.dp else 96.dp)
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                windowOffset = Offset(bounds.left, bounds.top)
                onBoundsChanged(bounds)
            }
            // A stable key (not `word`) keeps this gesture alive when the slot empties on drag
            // start; the parent's onDragStart no-ops when the slot is empty.
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        // windowOffset is the node's live top-left in window space and start is
                        // relative to that node, so their sum is the pointer in window space.
                        onDragStart(windowOffset + start)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Report the frame-to-frame delta instead of an absolute position. The
                        // parent accumulates it onto the pointer, so the dragged card stays locked
                        // under the finger even as other chips reflow and this node moves.
                        onDrag(dragAmount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (word == null) {
                Text(
                    text = BLANK,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = word.emoji, style = MaterialTheme.typography.titleLarge)
                    Text(text = word.hanzi, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun WordChip(
    word: StudyWord,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
) {
    var windowOffset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                windowOffset = Offset(bounds.left, bounds.top)
            }
            .pointerInput(word) {
                detectTapGestures { onTap() }
            }
            .pointerInput(word) {
                detectDragGestures(
                    onDragStart = { start ->
                        // windowOffset is the node's live top-left in window space and start is
                        // relative to that node, so their sum is the pointer in window space.
                        onDragStart(windowOffset + start)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Report the frame-to-frame delta instead of an absolute position. The
                        // parent accumulates it onto the pointer, so the dragged card stays locked
                        // under the finger even as other chips reflow and this node moves.
                        onDrag(dragAmount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            }
            // While this word is being dragged it stays composed (so the gesture above keeps
            // running) but collapses to nothing so the other chips reflow to fill its place.
            .then(if (hidden) Modifier.size(0.dp) else Modifier),
    ) {
        if (!hidden) {
            WordChipContent(word = word, elevated = false)
        }
    }
}

/**
 * A single delightful row in the sentence breakdown: a big tappable emoji "bubble" that pronounces
 * the word, followed by its hanzi, pinyin, English meaning, and a friendly explanation.
 */
@Composable
private fun BreakdownRow(
    entry: WordBreakdown,
    onTap: () -> Unit,
    onPractice: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = entry.emoji, style = MaterialTheme.typography.headlineSmall)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = entry.hanzi,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = entry.pinyin,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Text(
                    text = entry.english,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entry.explanation.isNotBlank()) {
                    Text(
                        text = entry.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Practice pronouncing this individual word.
            IconButton(onClick = onPractice) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = stringResource(R.string.study_pronounce_word, entry.hanzi),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun WordChipContent(word: StudyWord, elevated: Boolean) {
    Card(
        elevation = if (elevated) {
            CardDefaults.cardElevation(defaultElevation = 8.dp)
        } else {
            CardDefaults.cardElevation()
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = word.emoji, style = MaterialTheme.typography.titleLarge)
            Text(text = word.hanzi, style = MaterialTheme.typography.titleMedium)
            Text(
                text = word.pinyin,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = word.english,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** True when [this] (the currently-spoken range) overlaps [other]. Null/empty ranges never match. */
private infix fun IntRange?.overlaps(other: IntRange): Boolean {
    if (this == null || isEmpty() || other.isEmpty()) return false
    return first <= other.last && other.first <= last
}


/**
 * Builds a single template [segment], highlighting only the individual characters (words) that fall
 * within the part of the sentence currently being spoken. This animates the Chinese text word by
 * word rather than highlighting the whole segment at once.
 *
 * @param segmentStart the offset of [segment] into the assembled sentence
 */
private fun highlightedSegment(
    segment: String,
    segmentStart: Int,
    speakingRange: IntRange?,
    highlightColor: Color,
): AnnotatedString {
    if (speakingRange == null || speakingRange.isEmpty()) {
        return AnnotatedString(segment)
    }
    return buildAnnotatedString {
        segment.forEachIndexed { i, c ->
            if (segmentStart + i in speakingRange) {
                withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                    append(c)
                }
            } else {
                append(c)
            }
        }
    }
}

/**
 * Computes the character ranges (into the assembled sentence) for each template segment and each
 * blank, mirroring exactly how [assembleSentence] concatenates segments and fills.
 *
 * @return a pair of (segment ranges, blank ranges)
 */
private fun sentenceUnitRanges(
    segments: List<String>,
    slots: List<StudyWord?>,
    blankCount: Int,
): Pair<List<IntRange>, List<IntRange>> {
    val segmentRanges = ArrayList<IntRange>(segments.size)
    val blankRanges = ArrayList<IntRange>(blankCount)
    var offset = 0
    segments.forEachIndexed { index, segment ->
        segmentRanges.add(offset until offset + segment.length)
        offset += segment.length
        if (index < blankCount) {
            val fill = slots.getOrNull(index)?.hanzi ?: BLANK
            blankRanges.add(offset until offset + fill.length)
            offset += fill.length
        }
    }
    return segmentRanges to blankRanges
}

/**
 * Builds the English sentence, highlighting the exact word(s) that line up with the part of the
 * Chinese sentence currently being spoken.
 *
 * When the model supplied an [alignment] that reproduces both assembled sentences, the matching
 * English span is highlighted precisely. Otherwise (no alignment, or the learner has swapped in
 * different words so the precomputed spans no longer line up) it falls back to a proportional
 * estimate so the two languages still animate together while the sentence is read aloud.
 */
private fun highlightedEnglish(
    english: String,
    assembledChinese: String,
    alignment: List<AlignmentChunk>,
    speakingRange: IntRange?,
    spokenLength: Int,
    highlightColor: Color,
): AnnotatedString {
    if (speakingRange == null || speakingRange.isEmpty() || english.isBlank()) {
        return AnnotatedString(english)
    }
    val alignedRange = alignedEnglishRange(english, assembledChinese, alignment, speakingRange)
    if (alignedRange != null) {
        return buildAnnotatedString {
            english.forEachIndexed { i, c ->
                if (i in alignedRange) {
                    withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                        append(c)
                    }
                } else {
                    append(c)
                }
            }
        }
    }
    if (spokenLength <= 0) return AnnotatedString(english)
    val words = english.split(" ")
    val mid = (speakingRange.first + speakingRange.last) / 2.0
    val fraction = (mid / spokenLength).coerceIn(0.0, 0.999)
    val target = (fraction * words.size).toInt().coerceIn(0, words.lastIndex)
    return buildAnnotatedString {
        words.forEachIndexed { index, word ->
            if (index > 0) append(" ")
            if (index == target) {
                withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                    append(word)
                }
            } else {
                append(word)
            }
        }
    }
}
