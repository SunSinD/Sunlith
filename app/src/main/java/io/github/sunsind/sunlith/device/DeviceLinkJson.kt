package io.github.sunsind.sunlith.device

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal data class ToolCall(val tool: String, val arguments: Map<String, Any?>)

internal object DeviceLinkJson {
    const val MAX_DEPTH = 12
    const val MAX_STRING = 300_000

    private val factory: JsonFactory = JsonFactoryBuilder()
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(MAX_DEPTH).maxStringLength(MAX_STRING).maxNumberLength(32).maxNameLength(128)
            .maxDocumentLength(DeviceLinkHttp.MAX_BODY.toLong()).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()

    fun parseCall(body: ByteArray): ToolCall {
        val root = parse(body) as? Map<*, *> ?: throw invalid()
        if (root.keys != setOf("tool", "arguments")) throw DeviceLinkError.badRequest("The request must contain only tool and arguments.")
        val tool = root["tool"] as? String ?: throw invalid()
        if (tool.length > 64) throw DeviceLinkError.badRequest("Unknown tool.")
        val arguments = root["arguments"] as? Map<*, *> ?: throw DeviceLinkError.badRequest("Tool arguments must be a JSON object.")
        @Suppress("UNCHECKED_CAST")
        return ToolCall(tool, arguments as Map<String, Any?>)
    }

    fun parse(bytes: ByteArray): Any? {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { throw invalid() }
        try {
            factory.createParser(text).use { parser ->
                val value = read(parser, parser.nextToken() ?: throw invalid(), 1)
                if (parser.nextToken() != null) throw invalid()
                return value
            }
        } catch (_: IOException) {
            throw invalid()
        }
    }

    private fun read(parser: JsonParser, token: JsonToken, depth: Int): Any? = when (token) {
        JsonToken.START_OBJECT -> {
            if (depth > MAX_DEPTH) throw invalid()
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                val next = parser.nextToken() ?: throw invalid()
                if (next == JsonToken.END_OBJECT) break
                if (next != JsonToken.FIELD_NAME) throw invalid()
                val name = parser.currentName() ?: throw invalid()
                if (map.containsKey(name)) throw invalid()
                map[name] = read(parser, parser.nextToken() ?: throw invalid(), depth + 1)
            }
            map
        }
        JsonToken.START_ARRAY -> {
            if (depth > MAX_DEPTH) throw invalid()
            val list = ArrayList<Any?>()
            while (true) {
                val next = parser.nextToken() ?: throw invalid()
                if (next == JsonToken.END_ARRAY) break
                if (list.size >= 10_000) throw invalid()
                list += read(parser, next, depth + 1)
            }
            list
        }
        JsonToken.VALUE_STRING -> parser.text
        JsonToken.VALUE_NUMBER_INT -> when (parser.numberType) {
            JsonParser.NumberType.INT, JsonParser.NumberType.LONG -> parser.longValue
            else -> throw invalid()
        }
        JsonToken.VALUE_NUMBER_FLOAT -> parser.doubleValue
        JsonToken.VALUE_TRUE -> true
        JsonToken.VALUE_FALSE -> false
        JsonToken.VALUE_NULL -> null
        else -> throw invalid()
    }

    fun write(value: Any?): ByteArray {
        val output = ByteArrayOutputStream()
        factory.createGenerator(output).use { writeValue(it, value) }
        return output.toByteArray()
    }

    private fun writeValue(generator: JsonGenerator, value: Any?) {
        when (value) {
            null -> generator.writeNull()
            is String -> generator.writeString(value)
            is Boolean -> generator.writeBoolean(value)
            is Int -> generator.writeNumber(value)
            is Long -> generator.writeNumber(value)
            is Map<*, *> -> {
                generator.writeStartObject()
                value.forEach { (key, item) -> generator.writeFieldName(key as String); writeValue(generator, item) }
                generator.writeEndObject()
            }
            is List<*> -> {
                generator.writeStartArray()
                value.forEach { writeValue(generator, it) }
                generator.writeEndArray()
            }
            else -> error("Unsupported JSON value.")
        }
    }

    private fun invalid() = DeviceLinkError.badRequest("The request body must be one strict UTF-8 JSON object.")
}
