package app.apt443.data.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.Closeable

/**
 * The console's talkback connection, used when the camera's own audio port is not reachable
 * from this TV. Audio frames go out as binary messages in the same ADTS framing the camera
 * expects over UDP.
 */
class TalkbackSocket private constructor() : Closeable {
    private val opened = CompletableDeferred<Unit>()
    private var socket: WebSocket? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.isCompleted) opened.completeExceptionally(t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!opened.isCompleted) {
                opened.completeExceptionally(
                    IllegalStateException("The console closed the speaker connection ($code)."),
                )
            }
        }
    }

    /** Waits for the console to accept the connection before any audio is written. */
    suspend fun awaitOpen() {
        withTimeout(OPEN_TIMEOUT_MS) { opened.await() }
    }

    fun send(frame: ByteArray) {
        val accepted = socket?.send(frame.toByteString()) == true
        if (!accepted) error("The console stopped accepting speaker audio.")
    }

    override fun close() {
        runCatching { socket?.close(NORMAL_CLOSURE, null) }
        socket = null
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 5_000L
        private const val NORMAL_CLOSURE = 1000

        /** Returns null when the console has no local admin session to open talkback with. */
        fun open(
            connect: (WebSocketListener) -> WebSocket?,
        ): TalkbackSocket? {
            val session = TalkbackSocket()
            val live = connect(session.listener) ?: return null
            session.socket = live
            return session
        }
    }
}
