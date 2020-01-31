package io.emeraldpay.polkadotcrawler.libp2p

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.function.Function
import java.util.function.Predicate
import kotlin.math.min

class SizePrefixed {

    companion object {
        private val log = LoggerFactory.getLogger(SizePrefixed::class.java)

        @JvmStatic
        fun Standard(): Converter {
            return Converter(StandardSize())
        }

        @JvmStatic
        fun Varint(): Converter {
            return Converter(VarintSize())
        }
    }

    class Converter(val prefix: SizePrefix<Int>) {

        fun scanForExpected(data: ByteBuf): Int {
            if (data.readableBytes() == 0) {
                return 0
            }
            val len = prefix.read(data)
            if (data.readableBytes() < len) {
                return len - data.readableBytes()
            }
            return scanForExpected(data.skipBytes(len))
        }

        fun fullyRead(): Predicate<ByteBuf> {
            var expect = 0
            return Predicate {
                var copy = it.slice()
                if (expect > 0) {
                    val len = min(expect, copy.readableBytes())
                    if (len == 0) {
                        throw IllegalStateException("0 to read")
                    }
                    copy = copy.skipBytes(len)
                    expect -= len
                    if (expect == 0) {
                        expect = scanForExpected(copy)
                    }
                } else {
                    expect = scanForExpected(copy)
                }
                return@Predicate expect == 0
            }
        }

        fun split(data: ByteBuf): List<ByteBuf> {
            val result = ArrayList<ByteBuf>()

            while (data.readableBytes() > 0) {
                var len = prefix.read(data)
                if (data.readableBytes() < len) {
                    log.warn("Have less than expected. Have ${data.readableBytes()} < $len requested")
                    len = data.readableBytes()
                }
                val copy = data.readBytes(len)
                result.add(copy)
            }

            return result
        }

        fun reader(): Function<Flux<ByteBuf>, Flux<ByteBuf>> {
            return Function { flux ->
                flux.map { it.copy() }
                        .bufferUntil(fullyRead())
                        .map {  list ->
                            if (list.size == 1) {
                                list.first()
                            } else {
                                Unpooled.wrappedBuffer(*list.toTypedArray())
                            }
                        }
                        .flatMap {
                            Flux.fromIterable(split(it))
                        }
                        .filter {
                            it.readableBytes() > 0
                        }
            }
        }

        fun write(bytes: ByteBuf): ByteBuf {
            return Unpooled.wrappedBuffer(
                    prefix.write(bytes.readableBytes()), bytes
            )
        }


        fun writer(): Function<Flux<ByteBuf>, Flux<ByteBuf>> {
            return Function { flux ->
                flux.map { bytes -> write(bytes) }
            }
        }
    }

    interface SizePrefix<T: Number> {
        fun read(input: ByteBuf): T;
        fun write(value: T): ByteBuf;
    }

    class StandardSize(): SizePrefix<Int> {
        override fun read(input: ByteBuf): Int {
            return input.readInt()
        }

        override fun write(value: Int): ByteBuf {
            return Unpooled.wrappedBuffer(ByteBuffer.allocate(4).putInt(value).array())
        }
    }

    class VarintSize(): SizePrefix<Int> {
        override fun read(input: ByteBuf): Int {
            val coded = CodedInputStream.newInstance(input.nioBuffer())
            val result = coded.readUInt32()
            input.skipBytes(coded.totalBytesRead)
            return result
        }

        override fun write(value: Int): ByteBuf {
            val buf = ByteArrayOutputStream()
            val input = CodedOutputStream.newInstance(buf)
            input.writeUInt32NoTag(value)
            input.flush()
            return Unpooled.wrappedBuffer(buf.toByteArray())
        }
    }

    class VarlongSize(): SizePrefix<Long> {
        override fun read(input: ByteBuf): Long {
            val coded = CodedInputStream.newInstance(input.nioBuffer())
            val result = coded.readUInt64()
            input.skipBytes(coded.totalBytesRead)
            return result
        }

        override fun write(value: Long): ByteBuf {
            val buf = ByteArrayOutputStream()
            val input = CodedOutputStream.newInstance(buf)
            input.writeUInt64NoTag(value)
            input.flush()
            return Unpooled.wrappedBuffer(buf.toByteArray())
        }
    }
}