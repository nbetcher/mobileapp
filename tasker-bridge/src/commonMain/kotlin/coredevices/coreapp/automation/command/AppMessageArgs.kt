package coredevices.coreapp.automation.command

import kotlinx.serialization.json.*

/** Lossless v1 dictionary wire format. Legacy d.* strings have ambiguous types and are rejected. */
internal object AppMessageArgs {
    fun decode(args: Map<String, String>): Map<Int, Any> {
        require(args.keys.none { it.startsWith("d.") }) { "use typed dict_json instead of d.* arguments" }
        val raw = requireNotNull(args["dict_json"]) { "missing dict_json" }
        require(raw.length <= 32_768) { "dictionary too large" }
        val obj = Json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("dict_json must be an object")
        require(obj.isNotEmpty() && obj.size <= 255) { "dictionary must contain 1..255 entries" }
        val result = linkedMapOf<Int, Any>()
        for ((key, value) in obj) {
            val unsignedKey = key.toUIntOrNull()
            require(unsignedKey != null && key == unsignedKey.toString()) { "invalid dictionary key '$key'" }
            result[unsignedKey.toInt()] = decodeValue(value)
        }
        return result
    }

    private fun decodeValue(element: JsonElement): Any {
        if (element is JsonPrimitive && element !is JsonNull) {
            if (element.isString) return string(element)
            return integer(element).toIntExact()
        }
        val typed = element as? JsonObject ?: throw IllegalArgumentException("unsupported dictionary value")
        require(typed.keys == setOf("type", "value")) { "typed values require only type and value" }
        val type = typed["type"] as? JsonPrimitive
        require(type?.isString == true) { "invalid tuple type" }
        val value = typed.getValue("value")
        return when (type.content) {
            "string" -> string(value)
            "int" -> integer(value).toIntExact()
            "uint" -> integer(value).also { require(it in 0..UInt.MAX_VALUE.toLong()) { "uint out of range" } }.toUInt()
            "bytes" -> {
                val bytes = value as? JsonArray ?: throw IllegalArgumentException("bytes requires an array")
                require(bytes.size <= 4096) { "byte tuple too large" }
                bytes.map { integer(it).also { n -> require(n in 0..255) { "byte out of range" } }.toByte() }.toByteArray()
            }
            else -> throw IllegalArgumentException("unsupported tuple type '${type.content}'")
        }
    }

    private fun string(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString) { "string requires a JSON string" }
        require('\u0000' !in value.content) { "string cannot contain NUL" }
        return value.content
    }

    private fun integer(value: JsonElement): Long {
        require(value is JsonPrimitive && !value.isString && value !is JsonNull) { "integer requires a JSON number" }
        return value.longOrNull ?: throw IllegalArgumentException("invalid integer")
    }

    private fun Long.toIntExact(): Int {
        require(this in Int.MIN_VALUE..Int.MAX_VALUE) { "int out of range" }
        return toInt()
    }
}
