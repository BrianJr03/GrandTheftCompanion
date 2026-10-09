package jr.brian.thorhud.net

import android.util.Log
import jr.brian.thorhud.model.GameState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * TCP client that connects to ThorBridge.asi running inside Game Native and
 * emits [GameState] frames parsed from newline-delimited JSON. The flow
 * reconnects on disconnect with exponential backoff capped at 5 s.
 *
 * The flow is cold — collect once from the Service. Backpressure is handled
 * by the collector (StateFlow.update drops older frames, which is correct
 * for a HUD).
 */
@Singleton
class GameStateClient @Inject constructor() {

    fun frames(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
    ): Flow<GameState> = flow {
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            coroutineContext.ensureActive()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    Log.i(TAG, "connected to $host:$port")
                    backoffMs = INITIAL_BACKOFF_MS

                    BufferedReader(InputStreamReader(socket.getInputStream())).use { reader ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            val frame = runCatching { json.decodeFromString<GameState>(line) }
                                .getOrNull() ?: continue
                            emit(frame)
                        }
                    }
                }
                Log.w(TAG, "stream ended, reconnecting")
            } catch (t: Throwable) {
                // Expected classes of failure: ConnectException (ASI not up),
                // SocketTimeoutException (connection stalled), IOException
                // (game crashed). Log and reconnect.
                Log.w(TAG, "connection lost: ${t.javaClass.simpleName}: ${t.message}")
            }

            kotlinx.coroutines.delay(backoffMs.milliseconds)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 47801

        private const val TAG = "GameStateClient"
        private const val CONNECT_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 5000
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 5000L

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
