package com.ali.haidara.lecteur

/** Découpe un texte (100 % hors ligne) : 1, 2 ou 3 mots, ou une phrase (mode 0). */
object Chunker {
    fun make(text: String, mode: Int): List<String> =
        if (mode in 1..3) byWords(text, mode) else sentences(text)

    private fun byWords(text: String, n: Int): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.chunked(n).map { it.joinToString(" ") }

    private fun sentences(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        return clean.split(Regex("(?<=[.!?…])\\s+")).flatMap { hardSplit(it, 300) }.filter { it.isNotBlank() }
    }

    private fun hardSplit(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val res = mutableListOf<String>()
        var rest = s
        while (rest.length > max) {
            var cut = rest.lastIndexOf(", ", max)
            if (cut < max / 3) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            res.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) res.add(rest)
        return res
    }
}
