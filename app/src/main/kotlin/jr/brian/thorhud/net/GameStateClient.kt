package jr.brian.thorhud.net

import android.util.Log
import jr.brian.thorhud.model.GameState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * TCP client that connects to ThorBridge.asi inside the GameHub Lite /
 * Winlator / GameNative Wine prefix. Emits [GameState] frames parsed
 * from newline-delimited JSON, reconnects with exponential backoff on
 * disconnect, exposes a [send] path for client->server commands, and
 * (Phase A UX) surfaces the latest connection error via [lastError] so
 * the HUD's setup screen can tell the user what to fix.
 */
@Singleton
class GameStateClient @Inject constructor() {

    private val writeMutex = Mutex()
    @Volatile private var currentOut: OutputStream? = null
    @Volatile private var currentSocket: Socket? = null
    private val cmdId = AtomicInteger(0)

    // A user-triggered retry drops a Unit here. The frames() loop
    // consumes it to short-circuit the backoff delay. CONFLATED means
    // a flurry of retries collapses into one — we only need the next
    // iteration to re-run its connect attempt.
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)

    // Null while idle / not yet attempted. On any connect failure or
    // mid-stream disconnect the reason lands here so the UI can show it.
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Force a reconnect. Closes the current socket (any in-progress
     * read/connect fails fast with that exception, which the frames()
     * loop catches like any other disconnect) and nudges the backoff
     * delay to end so the next connect attempt fires immediately.
     *
     * Use when the UI knows the connection is wedged — e.g. "not
     * connected" sticks on repeated weapon switches, which can happen
     * if the TCP state got half-open and the read timeout hasn't
     * tripped yet.
     */
    fun reconnect() {
        runCatching { currentSocket?.close() }
        reconnectSignal.trySend(Unit)
    }

    fun frames(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
    ): Flow<GameState> = flow {
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            coroutineContext.ensureActive()
            try {
                Socket().use { socket ->
                    currentSocket = socket
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    Log.i(TAG, "connected to $host:$port")
                    _lastError.value = null
                    backoffMs = INITIAL_BACKOFF_MS
                    currentOut = socket.getOutputStream()
                    try {
                        BufferedReader(InputStreamReader(socket.getInputStream())).use { reader ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val line = reader.readLine() ?: break
                                if (line.isBlank()) continue
                                val frame = runCatching { json.decodeFromString<GameState>(line) }
                                    .onFailure { Log.w(TAG, "parse failed on: $line") }
                                    .getOrNull() ?: continue
                                if (frame.isHello) {
                                    Log.i(TAG, "hello: version=${frame.fileVersion} " +
                                            "crc32=${frame.crc32} supported=${frame.supported}")
                                }
                                emit(frame)
                            }
                        }
                    } finally {
                        currentOut = null
                        currentSocket = null
                    }
                }
                Log.w(TAG, "stream ended, reconnecting")
                _lastError.value = "stream ended; reconnecting"
            } catch (t: Throwable) {
                val short = "${t.javaClass.simpleName}: ${t.message ?: "no detail"}"
                Log.w(TAG, "connection lost: $short")
                _lastError.value = short
            }
            val forced = withTimeoutOrNull(backoffMs) { reconnectSignal.receive() } != null
            backoffMs = if (forced) INITIAL_BACKOFF_MS
                        else min(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Send a client->server command. Returns the assigned command id if
     * written to the socket, or null if the client is not connected.
     * Caller correlates the id against the matching `ack` frame.
     *
     * [arg] is an unsigned 32-bit value carried in a Long; see
     * [commandFrame] for why it is not an Int.
     */
    suspend fun send(name: String, arg: Long): Int? = withContext(Dispatchers.IO) {
        val out = currentOut ?: return@withContext null
        val id = cmdId.incrementAndGet()
        val line = commandFrame(id, name, arg)
        if (line == null) {
            Log.w(TAG, "cmd $name not sent: argument $arg out of range")
            return@withContext null
        }
        writeMutex.withLock {
            try {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
                Log.i(TAG, "sent cmd id=$id name=$name arg=$arg")
                id
            } catch (t: Throwable) {
                Log.w(TAG, "send failed: ${t.message}")
                null
            }
        }
    }

    /**
     * String variant for the single command that carries a C string:
     * set_plate. Pre-validated by [commandFrameText]; a null return
     * means the input could not be formed into a safe wire line (bad
     * chars / too long).
     */
    suspend fun sendText(name: String, text: String): Int? = withContext(Dispatchers.IO) {
        val out = currentOut ?: return@withContext null
        val id = cmdId.incrementAndGet()
        val line = commandFrameText(id, name, text)
        if (line == null) {
            Log.w(TAG, "cmd $name not sent: text rejected")
            return@withContext null
        }
        writeMutex.withLock {
            try {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
                Log.i(TAG, "sent cmd id=$id name=$name text='$text'")
                id
            } catch (t: Throwable) {
                Log.w(TAG, "send failed: ${t.message}")
                null
            }
        }
    }

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
