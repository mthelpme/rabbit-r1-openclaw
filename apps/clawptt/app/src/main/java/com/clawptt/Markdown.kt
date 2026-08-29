package com.clawptt

/**
 * Strips Markdown so neither the TTS nor the panel reads/【shows】 asterisks, backticks,
 * headings, list bullets, link syntax, etc. Keeps the human-readable text.
 */
fun stripMarkdown(input: String): String {
    var t = input
    t = t.replace(Regex("```[\\s\\S]*?```"), " ")            // fenced code blocks
    t = t.replace(Regex("`([^`]+)`"), "$1")                    // inline code
    t = t.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")   // [text](url) / images -> text
    t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")        // # headings
    t = t.replace(Regex("(?m)^\\s{0,3}>\\s?"), "")             // > blockquotes
    t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")             // - bullet lists
    t = t.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")           // 1. numbered lists
    t = t.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")          // **bold**
    t = t.replace(Regex("(\\*|_)(.+?)\\1"), "$2")              // *italic*
    t = t.replace(Regex("~~(.+?)~~"), "$1")                    // ~~strike~~
    t = t.replace(Regex("[*_`~#>|]"), "")                      // stray symbols
    t = t.replace(Regex("[ \\t]{2,}"), " ")
    t = t.replace(Regex("\\n{3,}"), "\n\n")
    return t.trim()
}
