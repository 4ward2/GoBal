package com.danielealbano.androidremotecontrolmcp.services.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** What the speaker reports back to the session. */
interface SpeakerEvents {
    /** A sentence started playing. */
    fun onSentence(text: String)

    /** Everything queued for the current reply has been played (or the reply was cut off). */
    fun onReplyPlayed()
}

/**
 * Reads a reply aloud sentence by sentence, keeping a timeline of what was audible when so the
 * echo filter can recognise the phone's own voice in the microphone. Sentences arrive while the
 * model is still writing; [endOfReply] marks where one reply stops.
 */
class CallSpeaker(
    private val audio: CallAudio,
    private val tts: TtsEngine,
    private val voice: Int,
    private val events: SpeakerEvents,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val timeline = CopyOnWriteArrayList<SpokenSpan>()

    @Volatile
    private var stopped = false
    private var job: Job? = null

    /** The spans played so far, for [EchoFilter]. */
    fun spoken(): List<SpokenSpan> = timeline.toList()

    fun start(scope: CoroutineScope) {
        job = scope.launch { loop() }
    }

    fun enqueue(sentence: String) {
        queue.trySend(sentence)
    }

    /** Marks the end of a reply: once the audio drains, [SpeakerEvents.onReplyPlayed] fires. */
    fun endOfReply() {
        queue.trySend(END)
    }

    fun stop() {
        stopped = true
        queue.close()
        job?.cancel()
    }

    private suspend fun loop() {
        for (item in queue) {
            if (stopped) break
            if (item == END) {
                drain()
                events.onReplyPlayed()
                continue
            }
            events.onSentence(item)
            speak(item)
        }
    }

    private fun speak(sentence: String) {
        var start = -1L
        var end = -1L
        runCatching {
            tts.speak(sentence, voice) { chunk ->
                val (s, e) = audio.write(chunk)
                if (start < 0) start = s
                end = e
                !stopped
            }
        }
        if (start >= 0) {
            timeline += SpokenSpan(sentence, start, end)
            if (timeline.size > MAX_SPANS) timeline.removeAt(0)
        }
    }

    /** Waits until the queued audio has actually come out of the speaker. */
    private suspend fun drain() {
        while (!stopped && audio.queuedMs() > 0) delay(POLL_MS)
    }

    private companion object {
        const val END = "\u0000end\u0000"
        const val POLL_MS = 50L
        const val MAX_SPANS = 200
    }
}
