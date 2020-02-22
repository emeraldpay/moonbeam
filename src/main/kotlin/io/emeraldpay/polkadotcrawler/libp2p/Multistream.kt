package io.emeraldpay.polkadotcrawler.libp2p

import io.emeraldpay.polkadotcrawler.ByteBufferCommons
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.ByteBuffer
import java.util.function.Function
import java.util.function.Predicate

class Multistream {

    companion object {
        private val log = LoggerFactory.getLogger(Multistream::class.java)
        private val NAME = "/multistream/1.0.0"
        private val NAME_BYTES = NAME.toByteArray()
        private val NAMENL = "$NAME\n".toByteArray()
        private val NL: Byte = 0x0a
        private val LEN_PREFIX_SIZE = 1;
        private val NL_SIZE = 1;
        private val NA = byteArrayOf(0x03, 0x6e, 0x61, 0x0a)

        private val sizePrefix = SizePrefixed.Varint().prefix
    }

    /**
     * Create full multistream header, with multistream itself and protocol definition
     */
    fun multistreamHeader(protocol: String): ByteBuffer {
        val len1 = sizePrefix.write(NAME_BYTES.size + 1)
        val len2 = sizePrefix.write(protocol.length + 1)

        val result = ByteBuffer.allocate(NAME_BYTES.size + protocol.length + 2 + len1.remaining() + len2.remaining())
        result.put(len1)
        result.put(NAME_BYTES)
        result.put(NL)
        result.put(len2)
        result.put(protocol.toByteArray())
        result.put(NL)

        return result.flip()
    }

    /**
     * Create a plain multistream definition ("{len}protocol{\n}")
     */
    fun plainHeader(protocol: String): ByteBuffer {
        val len = sizePrefix.write(protocol.length + 1)
        val result = ByteBuffer.allocate(protocol.length + 1 + len.remaining())
        result.put(len)
        result.put(protocol.toByteArray())
        result.put(NL)
        return result.flip()
    }

    /**
     * Split multistream header into protocol definition elements. Maybe be one or two elements.
     */
    fun splitProtocol(input: ByteBuffer): List<String> {
        val pos = input.position()
        try {
            val result = ArrayList<String>(2)
            while (input.remaining() > 0 && result.size < 2) {
                val len = input.get().toInt()
                if (input.remaining() < len) {
                    return result
                }
                val protocol = ByteArray(len)
                input.get(protocol)
                result.add(String(protocol.copyOfRange(0, protocol.size - 1)))
            }
            return result
        } finally {
            input.position(pos)
        }
    }

    /**
     * Negotiate multistream to the specified protocol
     *
     * @param inbound inbound data
     * @param protocol supported protocol
     * @param handler applied to process result once protocol is negotiated
     * @return outbound data
     */
    fun negotiate(inbound: Flux<ByteBuffer>, protocol: String, handler: Function<Flux<ByteBuffer>, Flux<ByteBuffer>>): Flux<ByteBuffer> {
        return negotiate(inbound, protocol, handler, false)
    }

    private fun negotiate(inbound: Flux<ByteBuffer>, protocol: String, handler: Function<Flux<ByteBuffer>, Flux<ByteBuffer>>, initiated: Boolean): Flux<ByteBuffer> {
        var headerBuffer: ByteBuffer? = null
        var headerFound = false
        val allRead: Predicate<ByteBuffer> = Predicate { input ->
            if (headerFound) {
                true
            } else {
                if (input.hasRemaining()) {
                    headerBuffer = if (headerBuffer == null) {
                        input
                    } else {
                        ByteBufferCommons.join(headerBuffer!!, input)
                    }
                }
                headerFound = splitProtocol(headerBuffer!!).isNotEmpty()
                headerFound
            }
        }
        val result = inbound.filter(allRead)
                .switchOnFirst { signal, flux ->
                    if (signal.hasValue()) {
                        val header = splitProtocol(headerBuffer!!)
                        if (header.isEmpty()) {
                            return@switchOnFirst Flux.error<ByteBuffer>(IllegalStateException("Empty header"))
                        } else if (header.size > 2 || (header.size == 2 && initiated) || (header.size == 1 && !initiated)) {
                            return@switchOnFirst Flux.error<ByteBuffer>(IllegalStateException("Invalid header"))
                        }
                        val currentProtocol = header.last()
                        if (currentProtocol == protocol) {
                            val confirm = Mono.just(plainHeader(protocol))
                            val next = flux.skip(1).transform(handler)
                            Flux.concat(confirm, next)
                        } else {
                            Flux.concat(Mono.just(ByteBuffer.wrap(NA)), negotiate(flux.skip(1), protocol, handler, true))
                        }
                    } else {
                        Flux.empty<ByteBuffer>()
                    }
                }
        val handshake = if (initiated) {
            Mono.empty<ByteBuffer>()
        } else {
            Mono.just(plainHeader(NAME))
        }
        return Flux.concat(
                handshake, // notify other side that we operate multistream
                result // negotiations itself + handler processing
        )
    }

    /**
     * Expect protocol header from remote, ex. as a confirmation. Once header received it continues flux as is
     *
     * @param protocol Protocol Id
     * @param includesSizePrefix (optional, default true) if true then expect that each protocol line starts with it's length
     * @param onFound (option) additional publisher to subscribe when protocol found
     */
    fun readProtocol(protocol: String, includesSizePrefix: Boolean = true, onFound: Mono<Void>? = null): Function<Flux<ByteBuffer>, Flux<ByteBuffer>> {
        var headerBuffer: ByteBuffer? = null

        var headerSize = NAME_BYTES.size + NL_SIZE + protocol.length + NL_SIZE

        if (includesSizePrefix) {
            headerSize += LEN_PREFIX_SIZE * 2
        }

        val exp = ByteBuffer.allocate(headerSize)
        if (includesSizePrefix) {
            exp.put((NAME_BYTES.size + 1).toByte())
        }
        exp.put(NAME_BYTES)
        exp.put(NL)
        if (includesSizePrefix) {
            exp.put((protocol.length + 1).toByte())
        }
        exp.put(protocol.toByteArray())
        exp.put(NL)

        var headerFound = false

        val allRead: Predicate<ByteBuffer> = Predicate { input ->
            if (headerFound) {
                true
            } else {
                if (input.hasRemaining()) {
                    headerBuffer = if (headerBuffer == null) {
                        input
                    } else {
                        ByteBufferCommons.join(headerBuffer!!, input)
                    }
                }
                headerFound = headerBuffer != null && headerBuffer!!.remaining() >= headerSize
                headerFound
            }
        }
        return Function { flux ->
            flux.bufferUntil(allRead)
                    .flatMap { list ->
                        if (headerBuffer == null) {
                            //list always has 1 element after header was found
                            Flux.fromIterable(list)
                        } else {
                            val ref = headerBuffer!!
                            val result: ByteBuffer
                            headerBuffer = null
                            val expectedHeader: ByteBuffer = exp.flip()
                            val actualHeader: ByteBuffer = ByteBufferCommons.copy(ref, headerSize)

                            if (actualHeader != expectedHeader) {
                                System.err.println("Actual:\n" + ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(actualHeader)))
                                System.err.println("Expected:\n" + ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(expectedHeader)))
                                return@flatMap Mono.error<ByteBuffer>(IllegalStateException("Received invalid header"))
                            }
                            result = ByteBufferCommons.copy(ref, ref.remaining())
                            if (onFound != null) {
                                onFound.thenReturn(result)
                            } else {
                                Mono.just(result)
                            }
                        }
                    }
                    .filter {
                        // skip if header took whole space
                        it.remaining() > 0
                    }

        }
    }

}