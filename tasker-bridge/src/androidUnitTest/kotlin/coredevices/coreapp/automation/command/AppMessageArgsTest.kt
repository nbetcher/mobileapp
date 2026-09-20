package coredevices.coreapp.automation.command

import kotlin.test.*

class AppMessageArgsTest {
    private fun decode(raw: String) = AppMessageArgs.decode(mapOf("dict_json" to raw))

    @Test fun preservesStringEmptyNumericLookingSignedUnsignedAndBytes() {
        val dict = decode("""{"0":"0123","1":"","2":-2147483648,"3":{"type":"uint","value":4294967295},"4":{"type":"bytes","value":[0,127,128,255]},"4294967295":{"type":"string","value":"123"}}""")
        assertEquals("0123", dict[0])
        assertEquals("", dict[1])
        assertEquals(Int.MIN_VALUE, dict[2])
        assertEquals(UInt.MAX_VALUE, dict[3])
        assertContentEquals(byteArrayOf(0,127,-128,-1), dict[4] as ByteArray)
        assertEquals("123", dict[-1])
        assertEquals(0, (decode("""{"0":{"type":"bytes","value":[]}}""")[0] as ByteArray).size)
        assertEquals("", decode("""{"0":{"type":"string","value":""}}""")[0])
        assertEquals(Int.MAX_VALUE, decode("""{"0":{"type":"int","value":2147483647}}""")[0])
    }

    @Test fun invalidKeysValuesAndTypesAreRejectedWithoutCoercion() {
        val invalid = listOf("{}", "[]", "null", "bad", """{"-1":1}""", """{"01":1}""", """{"4294967296":1}""",
            """{"a":1}""", """{"1":null}""", """{"1":true}""", """{"1":1.2}""", """{"1":2147483648}""",
            """{"1":{"type":"float","value":1}}""", """{"1":{"type":"int","value":"1"}}""",
            """{"1":{"type":"uint","value":-1}}""", """{"1":{"type":"uint","value":4294967296}}""",
            """{"1":{"type":"string","value":1}}""", """{"1":{"type":"bytes","value":[256]}}""",
            """{"1":{"type":"bytes","value":[-1]}}""", """{"1":{"type":"bytes","value":["0"]}}""",
            """{"1":{"type":"bytes","value":"AA=="}}""", """{"1":{"type":"int","value":1,"extra":0}}""",
            """{"1":"\u0000"}""")
        for (raw in invalid) assertFailsWith<IllegalArgumentException>(raw) { decode(raw) }
        assertFailsWith<IllegalArgumentException> { AppMessageArgs.decode(mapOf("d.0" to "123")) }
        assertFailsWith<IllegalArgumentException> { AppMessageArgs.decode(emptyMap()) }
    }

    @Test fun limitsDictionarySize() {
        assertFailsWith<IllegalArgumentException> { decode((0..255).joinToString(",", "{", "}") { "\"$it\":1" }) }
        assertFailsWith<IllegalArgumentException> { decode("{\"0\":\"" + "a".repeat(32768) + "\"}") }
        assertFailsWith<IllegalArgumentException> { decode("{\"0\":{\"type\":\"bytes\",\"value\":[" + List(4097){"0"}.joinToString(",") + "]}}") }
    }
}
