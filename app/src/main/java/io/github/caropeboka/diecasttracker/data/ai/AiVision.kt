package io.github.caropeboka.diecasttracker.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

/** What a provider read; [by] is its label, [provider] which one. */
class AiAnswer(val data: JsonObject, val provider: AiProvider, val by: String)

/**
 * Reads photos with the providers in the user's order (web: `AiVision.php`, which has one provider at a time):
 * when one can't answer — out of quota, busy, too slow, a key it refuses — the next one gets the same request.
 * One that ran out of quota is set aside until its quota is back, so the next requests start with one that works.
 */
class AiVision(
    private val setup: Flow<AiSetup>,
    private val gemini: GeminiClient,
    private val openAi: OpenAiClient,
    private val rests: ProviderRests,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val enabled: Flow<Boolean> = setup.map { it.isEnabled }.distinctUntilChanged()

    suspend fun isEnabled(): Boolean = setup.first().isEnabled

    /** Asks about some JPEGs, as [GeminiClient.extract]; throws [NOT_SET] as an [AiException] when no provider is set up. */
    suspend fun extract(
        images: List<ByteArray>,
        system: String,
        instruction: String,
        schema: JsonObject,
        onEvent: (AiEvent) -> Unit = {},
    ): AiAnswer {
        val chain = setup.first().chain
        if (chain.isEmpty()) throw AiException(NOT_SET)

        // Resting ones go last rather than not at all: their quota may be back early, and with one provider
        // there is nothing else to try.
        val now = clock()
        val rested = chain.associate { it.provider to rests.until(it.provider) }
        val (resting, ready) = chain.partition { rested.getValue(it.provider) > now }
        if (ready.isNotEmpty()) resting.forEach { onEvent(AiEvent.Resting(it.label, rested.getValue(it.provider))) }

        val misses = mutableListOf<AiException>()
        for ((index, provider) in (ready + resting).withIndex()) {
            if (index > 0) onEvent(AiEvent.Switching(provider.label))
            onEvent(AiEvent.Provider(provider.label))
            try {
                val data = when (provider.provider) {
                    AiProvider.Gemini -> gemini.extract(provider.key.orEmpty(), provider.model, images, system, instruction, schema, onEvent)
                    else -> openAi.extract(provider, images, system, instruction, schema, onEvent)
                }
                if (rested.getValue(provider.provider) != 0L) rests.clear(provider.provider)
                return AiAnswer(data, provider.provider, provider.label)
            } catch (e: AiException) {
                misses += e
                (e as? AiRetryLaterException)?.restUntil?.let { rests.rest(provider.provider, it) }
            }
        }
        throw failure(misses)
    }

    /**
     * One provider: its own error. Several: all their reasons, as "try later" when any of them only needs time (so
     * the bulk scan waits), and as "offline" only when none could be reached.
     */
    private fun failure(misses: List<AiException>): AiException {
        misses.singleOrNull()?.let { return it }
        val message = "Semua AI yang diatur gagal:\n" + misses.joinToString("\n") { "• ${it.message}" }
        return when {
            misses.all { it is AiOfflineException } -> AiOfflineException(misses.first().message.orEmpty())
            misses.any { it is AiRetryLaterException || it is AiOfflineException } -> AiRetryLaterException(message)
            else -> AiException(message)
        }
    }

    companion object {
        const val NOT_SET = "AI belum aktif. Isi API key di Pengaturan AI."
    }
}
