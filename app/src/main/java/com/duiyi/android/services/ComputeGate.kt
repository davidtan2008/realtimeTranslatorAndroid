package com.duiyi.android.services

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "the 4 big cores are busy with a translation".
 *
 * Gemma and SenseVoice are pinned to the same big cores. SenseVoice re-decodes
 * the whole accumulated utterance once a second while the speaker talks, so a
 * partial decode landing inside a generation steals cycles from it. Both sides
 * need to agree on when that is happening, and neither should depend on the other.
 *
 * Nothing here allocates or locks: reads happen on the audio path.
 */
@Singleton
class ComputeGate @Inject constructor() {
    private val translating = AtomicBoolean(false)

    /**
     * Whether the speech path should hold off on partial decodes while a generation runs.
     *
     * Defaults to on, but it is switchable at runtime so the two behaviours can be compared
     * on the same conversation: with it off, the latency probe records exactly how much a
     * partial decode overlapping a generation costs (and how often it happens). Without
     * that A/B the "partials are starving Gemma" claim stays an inference.
     */
    @Volatile
    var skipPartialsWhileTranslating: Boolean = true

    val translationInFlight: Boolean
        get() = translating.get()

    fun beginTranslation() {
        translating.set(true)
    }

    fun endTranslation() {
        translating.set(false)
    }
}
