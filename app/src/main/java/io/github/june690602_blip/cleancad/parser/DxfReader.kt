package io.github.june690602_blip.cleancad.parser

data class GroupCode(val code: Int, val value: String)

class DxfReader(input: String) {
    private val lines = input.lines()
    private var pos = 0

    fun hasNext(): Boolean {
        var p = pos
        while (p < lines.size && lines[p].isBlank()) p++
        return p + 1 < lines.size
    }

    fun next(): GroupCode {
        while (pos < lines.size && lines[pos].isBlank()) pos++
        require(pos + 1 < lines.size) { "DxfReader exhausted at position $pos (${lines.size} lines)" }
        val code = lines[pos].trim().toInt()
        val value = lines[pos + 1].trim()
        pos += 2
        return GroupCode(code, value)
    }

    fun peek(): GroupCode? {
        var p = pos
        while (p < lines.size && lines[p].isBlank()) p++
        if (p + 1 >= lines.size) return null
        return try {
            GroupCode(lines[p].trim().toInt(), lines[p + 1].trim())
        } catch (_: NumberFormatException) {
            null
        }
    }
}
