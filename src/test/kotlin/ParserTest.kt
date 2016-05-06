package ru.yole.jkid

import org.junit.Test
import ru.yole.jkid.ParserTest.JsonParseCallbackCall.*
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParserTest {
    @Test fun testTrivial() {
        verifyParse("""{"s": "x"}""", VisitProperty("s", Token.StringValue("x")))
    }

    @Test fun testTwoProperties() {
        verifyParse("""{"s": "x", "f": 1}""",
                VisitProperty("s", Token.StringValue("x")),
                VisitProperty("f", Token.NumberValue(1.0)))
    }

    @Test fun testMissingComma() {
        verifyMalformed("""{"s": "x" "f": 1}""")
    }

    @Test fun testNestedObject() {
        verifyParse("""{"s": {"x": 1}}""",
                EnterObject("s"),
                VisitProperty("x", Token.NumberValue(1.0)),
                LeaveObject)
    }

    @Test fun testArray() {
        verifyParse("""{"s": [1, 2]}""",
                EnterArray("s"),
                VisitArrayElement(Token.NumberValue(1.0)),
                VisitArrayElement(Token.NumberValue(2.0)),
                LeaveArray)
    }

    private fun verifyParse(json: String, vararg expectedCallbackCalls: JsonParseCallbackCall) {
        val reportingCallback = ReportingParseCallback()
        Parser(StringReader(json), reportingCallback).parse()
        assertEquals(expectedCallbackCalls.size, reportingCallback.calls.size)
        for ((expected, actual) in expectedCallbackCalls zip reportingCallback.calls) {
            assertEquals(expected, actual)
        }
    }

    private fun verifyMalformed(text: String) {
        assertFailsWith<MalformedJSONException> {
            Parser(StringReader(text), ReportingParseCallback()).parse()
        }
    }

    interface JsonParseCallbackCall {
        data class EnterObject(val propertyName: String) : JsonParseCallbackCall
        data class EnterArray(val propertyName: String) : JsonParseCallbackCall
        data class VisitProperty(val propertyName: String, val value: Token.ValueToken) : JsonParseCallbackCall
        data class VisitArrayElement(val value: Token.ValueToken) : JsonParseCallbackCall
        object LeaveObject : JsonParseCallbackCall
        object LeaveArray : JsonParseCallbackCall
    }

    class ReportingParseCallback: JsonParseCallback {
        val calls = mutableListOf<JsonParseCallbackCall>()

        override fun enterObject(propertyName: String) {
            calls.add(EnterObject(propertyName))
        }

        override fun leaveObject() {
            calls.add(LeaveObject)
        }

        override fun enterArray(propertyName: String) {
            calls.add(EnterArray(propertyName))
        }

        override fun visitArrayElement(value: Token.ValueToken) {
            calls.add(VisitArrayElement(value))
        }

        override fun leaveArray() {
            calls.add(LeaveArray)
        }

        override fun visitProperty(propertyName: String, value: Token.ValueToken) {
            calls.add(VisitProperty(propertyName, value))
        }
    }
}