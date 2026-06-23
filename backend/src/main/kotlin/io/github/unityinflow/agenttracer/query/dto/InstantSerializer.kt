package io.github.unityinflow.agenttracer.query.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * kotlinx-serialization serializer for [java.time.Instant], emitting epoch-millis as a JSON number.
 *
 * The query DTOs keep `Instant` typed properties (the Wave-0 test contract compares against
 * `Instant` values directly) while still being `@Serializable` for the Ktor JSON boundary mounted
 * in Plan 04. Epoch-millis is the wire shape the React timeline/DAG views consume.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeLong(value.toEpochMilli())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}
