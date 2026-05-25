package io.github.june690602_blip.cleancad.parser

data class GroupCode(val code: Int, val value: String)

class DxfReader(input: String) {
    // 빈 줄 제거: DXF 파서가 빈 줄에서 NumberFormatException 발생하지 않도록
    private val lines = input.lines().filter { it.isNotBlank() }
    private var pos = 0

    fun hasNext(): Boolean = pos + 1 < lines.size

    fun next(): GroupCode {
        val code = lines[pos].trim().toInt()
        val value = lines[pos + 1].trim()
        pos += 2
        return GroupCode(code, value)
    }

    fun peek(): GroupCode? {
        if (pos + 1 >= lines.size) return null
        return try {
            GroupCode(lines[pos].trim().toInt(), lines[pos + 1].trim())
        } catch (_: NumberFormatException) {
            null
        }
    }
}
