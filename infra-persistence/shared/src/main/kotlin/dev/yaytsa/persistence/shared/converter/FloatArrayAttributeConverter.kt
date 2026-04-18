package dev.yaytsa.persistence.shared.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.sql.Array as SqlArray

@Converter
class FloatArrayAttributeConverter : AttributeConverter<FloatArray?, Any?> {
    override fun convertToDatabaseColumn(attribute: FloatArray?): Any? = attribute

    override fun convertToEntityAttribute(dbData: Any?): FloatArray? {
        if (dbData == null) return null
        return when (dbData) {
            is SqlArray -> {
                val array = dbData.array
                when (array) {
                    is Array<*> -> FloatArray(array.size) { (array[it] as Number).toFloat() }
                    else -> null
                }
            }
            is Array<*> -> FloatArray(dbData.size) { (dbData[it] as Number).toFloat() }
            is String -> parseFloatArrayString(dbData)
            else -> null
        }
    }

    private fun parseFloatArrayString(raw: String): FloatArray? {
        val trimmed = raw.trim()
        if (trimmed == "{}" || trimmed == "[]") return floatArrayOf()
        val inner = trimmed.removeSurrounding("{", "}").removeSurrounding("[", "]")
        return inner.split(",").map { it.trim().toFloat() }.toFloatArray()
    }
}
