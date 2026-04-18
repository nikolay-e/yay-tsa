package dev.yaytsa.persistence.shared.converter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StringListAttributeConverterTest {
    private val converter = StringListAttributeConverter()

    @Test
    fun `empty PG array returns empty list`() {
        assertEquals(emptyList(), converter.convertToEntityAttribute("{}"))
    }

    @Test
    fun `simple PG array without quotes`() {
        assertEquals(listOf("rock", "jazz", "blues"), converter.convertToEntityAttribute("{rock,jazz,blues}"))
    }

    @Test
    fun `PG array with quoted values containing commas`() {
        assertEquals(
            listOf("rock, pop", "jazz", "r&b, soul"),
            converter.convertToEntityAttribute("""{"rock, pop","jazz","r&b, soul"}"""),
        )
    }

    @Test
    fun `PG array with escaped quotes inside values`() {
        assertEquals(
            listOf("""say "hello"""", "test"),
            converter.convertToEntityAttribute("""{"say \"hello\"","test"}"""),
        )
    }

    @Test
    fun `null input returns empty list`() {
        assertEquals(emptyList(), converter.convertToEntityAttribute(null))
    }

    @Test
    fun `single element array`() {
        assertEquals(listOf("rock"), converter.convertToEntityAttribute("{rock}"))
    }

    @Test
    fun `empty list converts to empty typed array`() {
        val result = converter.convertToDatabaseColumn(emptyList())
        val array = result as Array<*>
        assertEquals(0, array.size)
    }

    @Test
    fun `null attribute converts to null`() {
        val result = converter.convertToDatabaseColumn(null)
        assertEquals(null, result)
    }

    @Test
    fun `round-trip through string parsing preserves values`() {
        val original = listOf("rock", "jazz", "electronic")
        val dbValue = converter.convertToDatabaseColumn(original) as Array<*>
        assertEquals(original, dbValue.toList())
    }

    @Test
    fun `square bracket array returns empty list`() {
        assertEquals(emptyList(), converter.convertToEntityAttribute("[]"))
    }
}
