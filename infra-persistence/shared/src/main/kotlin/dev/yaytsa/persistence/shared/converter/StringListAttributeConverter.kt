package dev.yaytsa.persistence.shared.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.sql.Array as SqlArray

@Converter
class StringListAttributeConverter : AttributeConverter<List<String>, Any?> {
    override fun convertToDatabaseColumn(attribute: List<String>?): Any? = attribute?.toTypedArray()

    override fun convertToEntityAttribute(dbData: Any?): List<String> {
        if (dbData == null) return emptyList()
        return when (dbData) {
            is SqlArray -> {
                val array = dbData.array
                when (array) {
                    is Array<*> -> array.filterIsInstance<String>()
                    else -> emptyList()
                }
            }
            is Array<*> -> dbData.filterIsInstance<String>()
            is String -> parseTextArrayString(dbData)
            else -> emptyList()
        }
    }

    private fun parseTextArrayString(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed == "{}" || trimmed == "[]") return emptyList()
        val inner = trimmed.removeSurrounding("{", "}").removeSurrounding("[", "]")
        val result = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            when {
                inner[i] == '"' -> {
                    val sb = StringBuilder()
                    i++ // skip opening quote
                    while (i < inner.length && inner[i] != '"') {
                        if (inner[i] == '\\' && i + 1 < inner.length) {
                            sb.append(inner[i + 1])
                            i += 2
                        } else {
                            sb.append(inner[i])
                            i++
                        }
                    }
                    i++ // skip closing quote
                    result.add(sb.toString())
                }
                inner[i] == ',' || inner[i] == ' ' -> i++
                else -> {
                    val start = i
                    while (i < inner.length && inner[i] != ',') i++
                    result.add(inner.substring(start, i).trim())
                }
            }
        }
        return result.filter { it.isNotEmpty() }
    }
}
