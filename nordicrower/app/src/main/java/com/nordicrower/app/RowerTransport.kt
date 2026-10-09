package com.nordicrower.app

import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** Log transport shape/timing, not raw identity, security or telemetry payloads. */
class RowerTransport(private val delegate: HidTransport, private val productId: Int) : HidTransport {
    private var sent = 0L
    private var received = 0L
    private var timeouts = 0L
    private var lastSummary = 0L
    override val isOpen get() = delegate.isOpen
    override suspend fun open() = delegate.open()
    override suspend fun clearBuffer() = delegate.clearBuffer()
    override suspend fun write(data: ByteArray) {
        val started = System.nanoTime()
        try {
            delegate.write(data)
            sent++
            if (sent <= 20) RowerLog.d("USB", "TX pid=$productId bytes=${data.size} " +
                "header=${data.take(3).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }} " +
                "elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            summarize()
        } catch (e: Exception) {
            RowerLog.e("USB", "TX failed bytes=${data.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000}", e)
            throw e
        }
    }
    override suspend fun readPacket(): ByteArray? = try {
        delegate.readPacket().also {
            if (it == null) timeouts++ else received++
            summarize()
        }
    } catch (e: Exception) { RowerLog.e("USB", "RX failed", e); throw e }
    override fun incoming(): Flow<ByteArray> = delegate.incoming().onEach { received++; summarize() }
    private fun summarize() {
        val now = System.nanoTime() / 1_000_000
        if (now - lastSummary >= 5000) {
            lastSummary = now
            RowerLog.d("USB", "Transport counters TX=$sent RX=$received readTimeouts=$timeouts")
        }
    }
    override suspend fun close() {
        RowerLog.i("USB", "Releasing transport TX=$sent RX=$received readTimeouts=$timeouts")
        delegate.close()
    }
}
