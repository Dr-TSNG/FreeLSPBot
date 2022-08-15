package util

import kotlinx.coroutines.delay
import java.time.Duration
import java.util.concurrent.TimeoutException

class Retry<T>(private val block: suspend () -> T) {

    private var limit = 3
    private var exceptionHandler: (suspend (Throwable) -> Unit)? = null
    private var lastError: Throwable? = null

    fun limit(limit: Int): Retry<T> {
        this.limit = limit
        return this
    }

    fun onFailure(handler: suspend (Throwable) -> Unit): Retry<T> {
        exceptionHandler = handler
        return this
    }

    suspend operator fun invoke(delayOnFailure: Duration): T {
        while (limit != 0) {
            if (limit > 0) limit--
            runCatching {
                block()
            }.onSuccess {
                return it
            }.onFailure {
                lastError = it
                exceptionHandler?.invoke(it)
            }
            delay(delayOnFailure.toMillis())
        }
        throw TimeoutException("Retry limit exceeded").apply {
            initCause(lastError)
        }
    }
}

fun <T> retry(block: suspend () -> T) = Retry(block)
