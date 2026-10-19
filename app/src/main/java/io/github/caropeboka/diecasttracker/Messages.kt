package io.github.caropeboka.diecasttracker

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Short confirmations ("… ditambahkan ke koleksi.") that should show after a screen closes, like the web
 * app's flash toasts. The app's root shows them as snackbars.
 */
class Messages {
    private val channel = Channel<String>(Channel.BUFFERED)

    val flow: Flow<String> = channel.receiveAsFlow()

    fun show(message: String) {
        channel.trySend(message)
    }
}
