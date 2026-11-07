package io.github.caropeboka.diecasttracker.data.ai

import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.locale.Texts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What a provider read; [by] is its label, [provider] which one. */
class AiAnswer(val data: JsonObject, val provider: AiProvider, val by: String)

/** One turn of a conversation: the collector's ([fromUser]) or the AI's. The photos go with the first. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/** A page the AI looked at while answering (Gemini's Google Search). */
@Serializable
data class WebSource(val title: String, val url: String)

/** What a provider said in a chat, with the pages it searched. */
class ChatReply(val text: String, val sources: List<WebSource>, val provider: AiProvider, val by: String)

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
    private val texts: Texts,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val enabled: Flow<Boolean> = setup.map { it.isEnabled }.distinctUntilChanged()

    suspend fun isEnabled(): Boolean = setup.first().isEnabled

    /** Asks about some JPEGs, as [GeminiClient.extract]; throws an [AiException] when no provider is set up. */
    suspend fun extract(
        images: List<ByteArray>,
        system: String,
        instruction: String,
        schema: JsonObject,
        onEvent: (AiEvent) -> Unit = {},
    ): AiAnswer = firstAnswer(onEvent) { provider ->
        val data = when (provider.provider) {
            AiProvider.Gemini -> gemini.extract(provider.key.orEmpty(), provider.model, images, system, instruction, schema, onEvent)
            else -> openAi.extract(provider, images, system, instruction, schema, onEvent)
        }
        AiAnswer(data, provider.provider, provider.label)
    }

    /**
     * A conversation about some JPEGs ([turns] end with the collector's question): the answer in words. Gemini may
     * search the web for it ([ChatReply.sources]); the others answer from what they know.
     */
    suspend fun chat(
        images: List<ByteArray>,
        system: String,
        turns: List<ChatTurn>,
        onEvent: (AiEvent) -> Unit = {},
    ): ChatReply = firstAnswer(onEvent) { provider ->
        if (provider.provider == AiProvider.Gemini) {
            val (text, sources) = gemini.chat(provider.key.orEmpty(), provider.model, images, system, turns, onEvent)
            ChatReply(text, sources, provider.provider, provider.label)
        } else {
            ChatReply(openAi.chat(provider, images, system, turns, onEvent), emptyList(), provider.provider, provider.label)
        }
    }

    /** [ask]s the providers in order until one answers; see the class comment. */
    private suspend fun <T> firstAnswer(onEvent: (AiEvent) -> Unit, ask: suspend (ProviderSettings) -> T): T {
        val chain = setup.first().chain
        if (chain.isEmpty()) throw AiException(texts(R.string.ai_err_not_set))

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
                val answer = ask(provider)
                if (rested.getValue(provider.provider) != 0L) rests.clear(provider.provider)
                return answer
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
        val message = texts(R.string.ai_err_all_failed) + "\n" + misses.joinToString("\n") { "• ${it.message}" }
        return when {
            misses.all { it is AiOfflineException } -> AiOfflineException(misses.first().message.orEmpty())
            misses.any { it is AiRetryLaterException || it is AiOfflineException } -> AiRetryLaterException(message)
            else -> AiException(message)
        }
    }
}
