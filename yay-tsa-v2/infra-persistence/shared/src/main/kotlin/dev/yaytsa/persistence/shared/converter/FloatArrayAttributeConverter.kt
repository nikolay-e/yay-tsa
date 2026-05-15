package dev.yaytsa.persistence.shared.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class FloatArrayAttributeConverter : AttributeConverter<FloatArray?, String?> {
    override fun convertToDatabaseColumn(attribute: FloatArray?): String? {
        if (attribute == null) return null
        return attribute.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    override fun convertToEntityAttribute(dbData: String?): FloatArray? {
        if (dbData == null) return null
        val trimmed = dbData.trim()
        if (trimmed == "{}" || trimmed == "[]") return floatArrayOf()
        val inner = trimmed.removeSurrounding("[", "]").removeSurrounding("{", "}")
        if (inner.isEmpty()) return floatArrayOf()
        return inner.split(",").map { it.trim().toFloat() }.toFloatArray()
    }
}
