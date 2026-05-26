package io.github.june690602_blip.cleancad.parser

import java.io.InputStream
import java.nio.charset.Charset

object DxfCharsetDetector {

    private const val SAMPLE_BYTES = 4096

    fun detect(input: InputStream): Charset {
        val sample = ByteArray(SAMPLE_BYTES)
        val read = input.read(sample)
        if (read <= 0) return Charsets.UTF_8
        val text = String(sample, 0, read, Charsets.ISO_8859_1)
        val regex = Regex(
            """\${'$'}DWGCODEPAGE\s*\R\s*3\s*\R\s*([^\r\n]+)""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(text) ?: return Charsets.UTF_8
        val codepage = match.groupValues[1].trim().uppercase()
        return codepageToCharset(codepage)
    }

    private fun codepageToCharset(codepage: String): Charset = when (codepage) {
        "ANSI_949", "DOS949"   -> charsetOr("MS949", Charsets.UTF_8)
        "ANSI_950"             -> charsetOr("Big5", Charsets.UTF_8)
        "ANSI_936"             -> charsetOr("GBK", Charsets.UTF_8)
        "ANSI_932"             -> charsetOr("Shift_JIS", Charsets.UTF_8)
        "ANSI_1250"            -> charsetOr("windows-1250", Charsets.UTF_8)
        "ANSI_1251"            -> charsetOr("windows-1251", Charsets.UTF_8)
        "ANSI_1252", "DOS1252" -> charsetOr("windows-1252", Charsets.UTF_8)
        "ANSI_1253"            -> charsetOr("windows-1253", Charsets.UTF_8)
        "ANSI_1254"            -> charsetOr("windows-1254", Charsets.UTF_8)
        "UTF8", "UTF-8"        -> Charsets.UTF_8
        else                   -> Charsets.UTF_8
    }

    private fun charsetOr(name: String, fallback: Charset): Charset =
        try { Charset.forName(name) } catch (_: Throwable) { fallback }
}
