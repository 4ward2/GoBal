package com.danielealbano.androidremotecontrolmcp.services.call

import android.os.SystemClock
import com.danielealbano.androidremotecontrolmcp.services.chat.ChatHttpServer
import com.danielealbano.androidremotecontrolmcp.services.speech.Decoder
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalPorts
import com.danielealbano.androidremotecontrolmcp.services.speech.LocalSttModel

/** The speech recogniser a call listens through, for as long as the call lasts. */
interface CallRecognizer {
    fun start(lang: String?)

    /** Takes one microphone buffer (16 kHz mono PCM). */
    fun feed(
        pcm: ShortArray,
        count: Int,
    )

    fun stop()
}

/**
 * Turns a growing recognition result into the call's events: a partial whenever the words change,
 * a final segment (stamped with when its first word was heard) when the sentence ends. Pure; the
 * clock is passed in.
 */
class SegmentTracker(
    private val events: SttEvents,
) {
    private var last = ""
    private var startedAt = 0L

    /** [text] is what the recogniser has for the current sentence at [nowMs]. */
    fun partial(
        text: String,
        nowMs: Long,
    ) {
        if (text.isEmpty() || text == last) return
        if (last.isEmpty()) startedAt = nowMs
        last = text
        events.onPartial(text)
    }

    /** The sentence is over; nothing is reported when no words were recognised. */
    fun endOfSentence(nowMs: Long) {
        if (last.isNotEmpty()) events.onFinal(HeardSegment(last, startedAt, nowMs))
        last = ""
    }
}

/**
 * The call's recogniser on phones without the rooted setup: the in-app model, fed straight from
 * the call's microphone loop. Sentence ends come from the recogniser's own endpoint rules.
 */
class LocalCallStt(
    private val events: SttEvents,
    private val model: LocalSttModel,
) : CallRecognizer {
    private val tracker = SegmentTracker(events)
    private var decoder: Decoder? = null

    override fun start(lang: String?) {
        runCatching { model.recognizer() }
            .onSuccess { decoder = Decoder(it, it.createStream("")) }
            .onFailure { events.onError(it.message ?: "识别引擎没有启动") }
    }

    // The microphone loop feeds while hanging up stops: never free the stream under a decode.
    @Synchronized
    override fun feed(
        pcm: ShortArray,
        count: Int,
    ) {
        val active = decoder ?: return
        active.accept(pcm, count)
        val now = SystemClock.elapsedRealtime()
        tracker.partial(active.text(), now)
        if (active.atEndpoint()) {
            tracker.endOfSentence(now)
            active.reset()
        }
    }

    @Synchronized
    override fun stop() {
        decoder?.release()
        decoder = null
    }
}

/** Picks the recogniser for a call: the rooted setup's when it is running, else the in-app one. */
object CallRecognizers {
    fun pick(
        events: SttEvents,
        model: LocalSttModel,
    ): CallRecognizer =
        when {
            LocalPorts.isOpen(ChatHttpServer.STT_PORT) -> SttStream(events)
            model.installed() -> LocalCallStt(events, model)
            else -> error(LocalSttModel.NOT_INSTALLED)
        }
}
