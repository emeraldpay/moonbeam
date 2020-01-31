package io.emeraldpay.polkadotcrawler.libp2p

import io.emeraldpay.polkadotcrawler.DebugCommons
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.extra.processor.TopicProcessor
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

class Mplex: AutoCloseable {

    companion object {
        private val log = LoggerFactory.getLogger(Mplex::class.java)

        private val VARINT_CONVERTER = SizePrefixed.VarintSize()
    }

    private val multistream = Multistream()
    private val seq = AtomicLong(1000);
    private val messages = TopicProcessor.create<Message>()
    private val outbound = TopicProcessor.create<ByteBuf>()

    fun start(): Publisher<ByteBuf> {
        val starter = multistream.headerFor("/mplex/6.7.0")
        outbound.onNext(starter)
        return outbound
    }

    override fun close() {
        messages.dispose()
        outbound.dispose()
    }

    fun parse(msg: ByteBuf): List<Message> {
        val result = ArrayList<Message>(1)
        while (msg.readableBytes() > 0) {
            val parsed = Message.decode(msg)
            result.add(parsed)
        }
        return result
    }

    fun onNext(input: ByteBuf) {
        try {
//            DebugCommons.trace("MPLEX INPUT", input)
            parse(input).forEachIndexed { i, msg ->
//                val ascii = msg.data.toString(Charset.defaultCharset())
//                        .replace(Regex("[^\\w/\\\\.-]"), ".")
//                        .toCharArray().joinToString(" ")
//                val hex = DebugCommons.toHex(msg.data)
//                DebugCommons.trace("MPLEX ${msg.header.flag} ${msg.header.id}", msg.data)
//                log.debug("mplex message $i ${msg.header.flag} ${msg.header.id}")
//                log.debug("      $hex")
//                log.debug("      $ascii")

                messages.onNext(msg)
            }
        } catch (e: java.lang.IllegalArgumentException) {
            log.warn("Invalid Mplex data")
        }
    }

    private fun getMessages(source: Flux<Message>, id: Long, flag: Flag): Flux<ByteBuf> {
        return Flux.from(source)
                .filter {
                    it.header.id == id && it.header.flag == flag
                }
                .map {
                    it.data
                }
    }

    fun <T> newStream(handler: Handler<T>): T {
        val id = seq.incrementAndGet()
        val stream: Publisher<ByteBuf> = getMessages(messages, id, Flag.MessageReceiver)
        val msg = Message(Header(Flag.NewStream, id), Unpooled.wrappedBuffer("stream $id".toByteArray()))
        outbound.onNext(msg.encode())
        return handler.handle(id, stream, MplexOutbound(id, true, outbound))
    }

    fun <T> receiveStreams(handler: Handler<T>): Flux<T> {
        val f = Flux.from(messages).share().cache(1)
        val result = Flux.from(f).filter {
                    it.header.flag == Flag.NewStream
                }
                .map { init ->
                    val id = init.header.id
                    val stream: Publisher<ByteBuf> = getMessages(f, id, Flag.MessageInitiator)
                    val outbound = MplexOutbound(id, false, outbound)
                    Flux.from(f)
                            .filter {
                                it.header.id == id && it.header.flag == Flag.CloseInitiator
                            }
                            .single()
                            .subscribe {
                                log.debug("Close stream $id")
                                outbound.close()
                            }
                    return@map handler.handle(id, stream, outbound)
                }
        return result
    }

    class Header(val flag: Flag, val id: Long) {
        fun encode(): ByteBuf {
            val value = id.shl(3) + flag.id
            return converter.write(value)
        }

        companion object {
            private val converter = SizePrefixed.VarlongSize()

            fun decode(input: ByteBuf): Header {
                val value = converter.read(input)
                val flagId = value.and(0x07);
                val id = value.shr(3)
                return Header(Flag.byId(flagId.toInt()), id)
            }
        }
    }

    class Message(val header: Header, val data: ByteBuf) {
        fun encode(): ByteBuf {
            val header = header.encode()
            val length = VARINT_CONVERTER.write(data.readableBytes())
            return Unpooled.wrappedBuffer(header, length, data)
        }

        companion object {
            private val converter = SizePrefixed.VarintSize()

            fun decode(input: ByteBuf): Message {
                val header = Header.decode(input)
                val len = VARINT_CONVERTER.read(input)
                val data = input.readSlice(len).retain()
                return Message(header, data)
            }
        }
    }

    enum class Flag(val id: Int) {
        NewStream( 0 ),
        MessageReceiver( 1 ),
        MessageInitiator( 2 ),
        CloseReceiver( 3 ),
        CloseInitiator( 4 ),
        ResetReceiver( 5 ),
        ResetInitiator( 6 );

        companion object {
            fun byId(id: Int): Flag {
                return Flag.values().find { it.id == id } ?: throw IllegalArgumentException("Invalid flag id: $id")
            }
        }
    }

    class MplexOutbound(val streamId: Long, val initiator: Boolean, private val outbound: TopicProcessor<ByteBuf>): AutoCloseable {

        fun send(value: Publisher<ByteBuf>): Mono<Void> {
            return Flux.from(value)
                    .map {
                        val flag = if (initiator) {
                            Flag.MessageInitiator
                        } else {
                            Flag.MessageReceiver
                        };
                        val msg = Message(Header(flag, streamId), it)
                        msg.encode()
                    }
                    .doOnNext {
                        outbound.onNext(it)
                    }
                    .then()
        }

        override fun close() {
            //TODO
        }
    }

    interface Handler<T> {
        fun handle(id: Long, inboud: Publisher<ByteBuf>, outboud: MplexOutbound): T
    }
}