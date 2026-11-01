package io.github.caropeboka.diecasttracker.data.ai

/** What a running AI request is doing, so the screen can show more than a spinner. */
sealed interface AiEvent {
    /** [name] (a provider's label) takes the request; the events after it are about that provider. */
    data class Provider(val name: String) : AiEvent

    /** [name] was set aside until [until] (epoch millis) after running out of quota; others go first. */
    data class Resting(val name: String, val until: Long) : AiEvent

    /** The last provider couldn't answer: [name] is tried next. */
    data class Switching(val name: String) : AiEvent

    /** The provider takes at most [sent] photos per request: [sent] of [total] go. */
    data class Trimmed(val sent: Int, val total: Int) : AiEvent

    /** The fallback list isn't cached yet: asking which models this key can use. */
    data object ListingModels : AiEvent

    /** Sending [bytes] (photos included) to [model]; [fallback] counts the models tried before it (0 = the chosen one). */
    data class Sending(val model: String, val bytes: Long, val fallback: Int) : AiEvent

    /** [sent] of [total] bytes are on their way. */
    data class Uploading(val sent: Long, val total: Long) : AiEvent

    /** Everything is sent; [model] is reading. */
    data class Waiting(val model: String) : AiEvent

    /** [model] didn't answer ([reason], in Indonesian); [retrying]: it gets one more go, otherwise the next model may. */
    data class Missed(val model: String, val reason: String, val retrying: Boolean) : AiEvent

    /** The answer is in and being read. */
    data object Answered : AiEvent
}
