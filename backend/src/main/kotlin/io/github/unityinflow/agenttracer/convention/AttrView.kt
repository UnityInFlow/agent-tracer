package io.github.unityinflow.agenttracer.convention

import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue

/**
 * Type-preserving read-only view over a list of OTLP [KeyValue] attributes.
 *
 * Each accessor reads ONLY the matching OTLP [AnyValue] oneof variant and returns
 * `null` when the key is absent OR carries a different variant — there is no
 * silent lexical coercion (strict per RESEARCH Q2; a `string_value` "1234"
 * requested as [long] yields `null`, not `1234L`). This preserves OTLP value
 * types (int → Long, double → Double, bool → Boolean) so downstream typed
 * columns survive (Pitfall 1). No accessor ever throws (Security V5 / DoS).
 */
class AttrView private constructor(
    private val attributes: Map<String, AnyValue>,
) {
    /** Reads a `string_value`; `null` if absent or a different variant. */
    fun string(key: String): String? = attributes[key]?.takeIf { it.hasStringValue() }?.stringValue

    /** Reads an `int_value` (int64) as [Long]; `null` if absent or a different variant. */
    fun long(key: String): Long? = attributes[key]?.takeIf { it.hasIntValue() }?.intValue

    /** Reads a `double_value`; `null` if absent or a different variant. */
    fun double(key: String): Double? = attributes[key]?.takeIf { it.hasDoubleValue() }?.doubleValue

    /** Reads a `bool_value`; `null` if absent or a different variant. */
    fun bool(key: String): Boolean? = attributes[key]?.takeIf { it.hasBoolValue() }?.boolValue

    /** Reads an `int_value` narrowed to [Int]; `null` if absent, wrong variant, or out of Int range. */
    fun int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    /**
     * Reads an `array_value` of `string_value` elements as a `List<String>`.
     *
     * Returns `null` if the key is absent or is not an array. Non-string elements
     * in the array are skipped (only `string_value` entries are collected).
     */
    fun stringList(key: String): List<String>? {
        val value = attributes[key]?.takeIf { it.hasArrayValue() } ?: return null
        return value.arrayValue.valuesList
            .filter { it.hasStringValue() }
            .map { it.stringValue }
    }

    companion object {
        /**
         * Builds an [AttrView] from an OTLP [KeyValue] list. Entries with no value
         * are skipped; on duplicate keys the last occurrence wins. Never throws.
         */
        fun of(attributes: List<KeyValue>): AttrView =
            AttrView(
                attributes
                    .filter { it.hasValue() }
                    .associate { it.key to it.value },
            )

        /** An empty view — every accessor returns `null`. */
        fun empty(): AttrView = AttrView(emptyMap())
    }
}
