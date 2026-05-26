package io.github.june690602_blip.cleancad.parser

import java.io.BufferedReader

data class GroupCode(val code: Int, val value: String)

/**
 * Streaming DXF group-code reader backed by a [BufferedReader].
 *
 * Reads one (code, value) pair at a time without loading the entire file into
 * memory.  The secondary [String] constructor is provided for small inline
 * fixtures used in unit tests.
 */
class DxfReader(private val reader: BufferedReader) {

    /** Convenience constructor for unit tests — wraps [input] in a [BufferedReader]. */
    constructor(input: String) : this(input.reader().buffered())

    // Two-line lookahead: codeLine holds the group-code, valueLine holds its value.
    private var codeLine: String? = null
    private var valueLine: String? = null

    init { advance() }

    /** Reads the next non-blank line from the underlying reader, or null at EOF. */
    private fun readNonBlank(): String? {
        var line = reader.readLine()
        while (line != null && line.isBlank()) line = reader.readLine()
        return line
    }

    /** Loads the next (code, value) pair into the lookahead buffer. */
    private fun advance() {
        codeLine  = readNonBlank()
        valueLine = if (codeLine != null) reader.readLine() else null
    }

    fun hasNext(): Boolean = codeLine != null && valueLine != null

    fun peek(): GroupCode? {
        val c = codeLine  ?: return null
        val v = valueLine ?: return null
        return try { GroupCode(c.trim().toInt(), v.trim()) }
        catch (_: NumberFormatException) { null }
    }

    fun next(): GroupCode {
        val c = requireNotNull(codeLine)  { "DxfReader exhausted" }
        val v = requireNotNull(valueLine) { "DxfReader exhausted" }
        advance()
        return try { GroupCode(c.trim().toInt(), v.trim()) }
        catch (e: NumberFormatException) {
            throw IllegalStateException("Invalid DXF group code: '$c'", e)
        }
    }
}
