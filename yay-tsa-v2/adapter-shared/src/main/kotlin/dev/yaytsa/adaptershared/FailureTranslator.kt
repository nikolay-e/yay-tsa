package dev.yaytsa.adaptershared

import dev.yaytsa.shared.Failure

interface FailureTranslator<T> {
    fun translate(failure: Failure): T
}
