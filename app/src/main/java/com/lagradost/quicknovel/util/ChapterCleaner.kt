package com.lagradost.quicknovel.util

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.text.Normalizer

/**
 * Removes noise that aggregator sites inject into chapters before they are packaged as an epub:
 *
 *  - translator / editor / proofreader credit lines ("Translator: X Editor: Y", "TL: X", ...)
 *  - site watermarks such as "The source of this content is freewebnovel.com", including
 *    obfuscated variants that use look-alike unicode characters (`Freewebnᴏvel.cᴏm`,
 *    `ｆｒｅｅｗｅｂｎｏｖｅｌ`, `𝐟𝐫𝐞𝐞𝐰𝐞𝐛𝐧𝐨𝐯𝐞𝐥`, Cyrillic/Greek homoglyphs, zero-width
 *    characters, `f.r.e.e.w.e.b.n.o.v.e.l`, `freewebnovel [dot] com`, ...)
 *
 * Matching is done on a "folded" copy of the text where every character is mapped to its plain
 * lowercase latin equivalent, while keeping an index map back to the original text so only the
 * offending part of the original is removed.
 */
object ChapterCleaner {
    /** Known aggregator names, matched even without a TLD. */
    private val knownSites = listOf(
        "freewebnovel", "libread", "novelbin", "novelfull", "novelfire", "lightnovelpub",
        "lightnovelworld", "lightnovelcave", "readlightnovel", "boxnovel", "mtlnovel",
        "novelhall", "readnovelfull", "allnovel", "novelnext", "novelbuddy", "wuxiaworld",
        "webnovelpub", "pandanovel", "freewn", "noveldex", "fastnovel", "bednovel",
        "novelusb", "readwn", "wuxiax", "novelpub",
    )

    /** Names that are also normal words, so only matched with a TLD (webnovel.com). */
    private val knownSitesRequiringTld = listOf("webnovel", "novelupdates", "scribblehub", "wuxiaworld", "royalroad")

    private val leet = mapOf(
        'o' to "[o0]", 'l' to "[l1i|]", 'i' to "[i1l|!]", 'e' to "[e3]",
        'a' to "[a4@]", 's' to "[s5$]", 't' to "[t7]",
    )

    // Separators that are injected between letters: "f r e e", "f.r.e.e", "f-r-e-e"
    private const val SEP = "[\\s._*\\-~]{0,2}"
    private const val STRICT_DOT =
        "(?:\\s{0,2}(?:\\.|\\(\\s*dot\\s*\\)|\\[\\s*dot\\s*\\]|\\{\\s*dot\\s*\\}|\\s\\s?dot\\s\\s?)\\s{0,2})"
    private val tlds = listOf(
        "com", "net", "org", "co", "io", "me", "xyz", "info", "cc", "top", "site", "online",
        "club", "vip", "ws", "to", "tv", "app", "pro",
    )

    private fun spaced(word: String): String =
        word.map { c -> leet[c] ?: Regex.escape(c.toString()) }.joinToString(SEP)

    private val tldPattern = tlds.sortedByDescending { it.length }.joinToString("|") { spaced(it) }

    private val commonTldPattern = listOf("com", "net", "org").joinToString("|") { spaced(it) }

    /** Group 1 or 2 is set when the match includes a TLD */
    private val knownSiteRegex: Regex = run {
        val bare = knownSites.sortedByDescending { it.length }.joinToString("|") { spaced(it) }
        val withTld =
            knownSitesRequiringTld.sortedByDescending { it.length }.joinToString("|") { spaced(it) }
        val domain = "$STRICT_DOT(?:$tldPattern)|\\s{1,2}(?:$commonTldPattern)"
        Regex(
            "(?<![a-z0-9])(?:(?:$bare)($domain)?|(?:$withTld)($domain))(?![a-z0-9])"
        )
    }

    /** Any "something.com" like domain, only removed when it is inside a watermark sentence. */
    private val genericDomainRegex = Regex(
        "(?<![a-z0-9@])(?:www$STRICT_DOT)?[a-z0-9][a-z0-9\\-]{2,40}$STRICT_DOT(?:com|net|org|xyz|info|top|site|online|club|vip|io|cc)(?![a-z0-9])"
    )

    private val watermarkKeywordRegex = Regex(
        "\\b(?:source|read|reading|visit|updated?|updates|publish(?:ed)?|chapters?|novels?|find|latest|" +
                "original(?:ly)?|stolen|steal|content|available|hosted|translat\\w*|website|site|" +
                "support|faster|first|exclusive|discover|enjoy|experience|follow|check out|copyright|" +
                "platform|moving|moved|domain|bookmark)\\b"
    )

    private val creditStartRegex = Regex(
        "^[^a-z0-9]*(?:" +
                "(?:translated|edited|proofread|proofed|checked|tl'?d|ed'?d|cleaned|typeset)\\s*by\\b" +
                "|(?:translators?|translations?|editors?|editing|proofreaders?|proofreading|" +
                "tlc|tl|ed|pr|qc|raws?|raw provider|cleaners?|typesetters?)\\s*[:：\\-–—|]" +
                ")"
    )

    private val creditInlineRegex = Regex(
        "\\b(?:translators?|editors?|proofreaders?)\\s*[:：]|\\b(?:translated|edited|proofread) by\\b"
    )

    private const val MAX_CREDIT_LINE = 200
    private const val MAX_WATERMARK_SENTENCE = 250

    // ------------------------------------------------------------------------------------------
    // Character folding
    // ------------------------------------------------------------------------------------------

    private val homoglyphs: Map<Int, String> = buildMap {
        fun add(chars: String, to: String) = chars.codePoints().forEach { put(it, to) }
        // Small capitals / phonetic letters
        add("ᴀ", "a"); add("ʙ", "b"); add("ᴄ", "c"); add("ᴅ", "d"); add("ᴇ", "e"); add("ꜰ", "f")
        add("ɢ", "g"); add("ʜ", "h"); add("ɪ", "i"); add("ᴊ", "j"); add("ᴋ", "k"); add("ʟ", "l")
        add("ᴍ", "m"); add("ɴ", "n"); add("ᴏ", "o"); add("ᴘ", "p"); add("ǫ", "q"); add("ʀ", "r")
        add("ꜱ", "s"); add("ᴛ", "t"); add("ᴜ", "u"); add("ᴠ", "v"); add("ᴡ", "w"); add("ʏ", "y")
        add("ᴢ", "z"); add("ɑ", "a"); add("ɡ", "g"); add("ɩı", "i"); add("ȷ", "j"); add("ł", "l")
        add("ꞁ", "l"); add("ø", "o"); add("ɵ", "o"); add("ƅ", "b"); add("ꮃ", "w")
        // Cyrillic look-alikes
        add("аА", "a"); add("вВ", "b"); add("еЕёЁ", "e"); add("кК", "k"); add("мМ", "m")
        add("нН", "h"); add("оО", "o"); add("рР", "p"); add("сС", "c"); add("тТ", "t")
        add("уУ", "y"); add("хХ", "x"); add("іІїЇ", "i"); add("јЈ", "j"); add("ѕЅ", "s")
        add("ԁ", "d"); add("ӏ", "l"); add("ԛ", "q"); add("ԝԜ", "w"); add("ү", "y"); add("һ", "h")
        add("ɡ", "g"); add("ь", "b"); add("пП", "n")
        // Greek look-alikes
        add("αΑ", "a"); add("βΒ", "b"); add("εΕ", "e"); add("ζΖ", "z"); add("ηΗ", "n")
        add("ιΙ", "i"); add("κΚ", "k"); add("μΜ", "m"); add("νΝ", "v"); add("οΟ", "o")
        add("ρΡ", "p"); add("τΤ", "t"); add("υΥ", "u"); add("χΧ", "x"); add("ϲ", "c")
        add("ω", "w")
        // Dots
        add("。．｡·•‧∙⋅․", ".")
    }

    private fun isInvisible(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.FORMAT.toInt()
                || type == Character.NON_SPACING_MARK.toInt()
                || type == Character.ENCLOSING_MARK.toInt()
                || type == Character.COMBINING_SPACING_MARK.toInt()
                || cp == 0x00AD // soft hyphen
                || cp == 0x034F // combining grapheme joiner
                || cp in 0xFE00..0xFE0F // variation selectors
    }

    private fun fold(cp: Int): String {
        if (cp < 0x80) return Character.toChars(cp)[0].lowercaseChar().toString()
        if (isInvisible(cp)) return ""
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return " "
        homoglyphs[cp]?.let { return it }
        val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)
        val sb = StringBuilder()
        decomposed.codePoints().forEach { d ->
            if (isInvisible(d)) return@forEach
            val mapped = homoglyphs[d]
            if (mapped != null) sb.append(mapped) else sb.appendCodePoint(d)
        }
        var out = sb.toString().lowercase()
        // Parenthesized letters "⒡" -> "(f)"
        if (out.length == 3 && out[0] == '(' && out[2] == ')') out = out[1].toString()
        return out
    }

    /** Folded text with, for every folded char, the [start, end) range in the original */
    private class Folded(val text: String, val starts: IntArray, val ends: IntArray)

    private fun foldWithMap(original: String): Folded {
        // Fast path, plain ascii maps 1:1
        if (original.all { it.code < 0x80 }) {
            val starts = IntArray(original.length) { it }
            val ends = IntArray(original.length) { it + 1 }
            return Folded(original.lowercase(), starts, ends)
        }
        val sb = StringBuilder(original.length)
        var starts = IntArray(original.length + 8)
        var ends = IntArray(original.length + 8)
        var size = 0
        var i = 0
        while (i < original.length) {
            val cp = original.codePointAt(i)
            val len = Character.charCount(cp)
            val f = fold(cp)
            for (c in f) {
                if (size == starts.size) {
                    starts = starts.copyOf(size * 2)
                    ends = ends.copyOf(size * 2)
                }
                sb.append(c)
                starts[size] = i
                ends[size] = i + len
                size++
            }
            i += len
        }
        return Folded(sb.toString(), starts.copyOf(size), ends.copyOf(size))
    }

    /** Plain lowercase latin version of the text, exposed for tests and debugging. */
    fun normalize(text: String): String = foldWithMap(text).text

    // ------------------------------------------------------------------------------------------
    // Text cleaning
    // ------------------------------------------------------------------------------------------

    private val sentenceEnd = charArrayOf('.', '!', '?', '。', '！', '？', '…')

    private fun sentenceStart(text: String, from: Int): Int {
        var i = from - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '\n') return i + 1
            if (c in sentenceEnd && i + 1 < text.length && text[i + 1].isWhitespace()) return i + 1
            i--
        }
        return 0
    }

    private fun sentenceEndIdx(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') return i
            if (c in sentenceEnd && (i + 1 >= text.length || text[i + 1].isWhitespace())) {
                var end = i + 1
                // include closing quotes / brackets / decorations after the punctuation
                while (end < text.length && !text[end].isLetterOrDigit() && !text[end].isWhitespace() && text[end] != '\n') end++
                return end
            }
            i++
        }
        return text.length
    }

    private fun hasLettersOrDigits(text: String): Boolean = text.any { it.isLetterOrDigit() }

    private val emptyBrackets = Regex("[(\\[{【〔（]\\s*[)\\]}】〕）]")
    private val multiSpace = Regex("[ \\t\\u00A0]{2,}")

    private fun tidy(text: String): String =
        text.replace(emptyBrackets, "").replace(multiSpace, " ")

    private fun sentenceHasKeyword(norm: String, match: MatchResult): Boolean {
        val s = sentenceStart(norm, match.range.first)
        val e = sentenceEndIdx(norm, match.range.last + 1)
        if (e - s > MAX_WATERMARK_SENTENCE) return false
        // The site name itself ("...novel") should not count as a keyword
        val rest = norm.substring(s, match.range.first) + " " + norm.substring(match.range.last + 1, e)
        return watermarkKeywordRegex.containsMatchIn(rest)
    }

    // ------------------------------------------------------------------------------------------
    // Cheap pre-filtering, as running the regexes on every text node is slow
    // ------------------------------------------------------------------------------------------

    private fun canonical(c: Char): Char? = when (c) {
        ' ', '\t', '\n', '\r', '.', '_', '*', '-', '~' -> null
        '0' -> 'o'
        '1', 'l', '|', '!' -> 'i'
        '3' -> 'e'
        '4', '@' -> 'a'
        '5', '$' -> 's'
        '7' -> 't'
        else -> c
    }

    private fun compact(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) canonical(c)?.let { sb.append(it) }
        return sb.toString()
    }

    private val compactSiteNames = (knownSites + knownSitesRequiringTld).map { compact(it) }

    private val tldHintRegex = Regex(
        "(?:\\.|dot\\s?[)\\]}]?)\\s{0,2}(?:com|net|org|xyz|info|top|site|online|club|vip|io|cc)(?![a-z0-9])"
    )

    private fun mayContainKnownSite(norm: String): Boolean {
        val c = compact(norm)
        return compactSiteNames.any { c.contains(it) }
    }

    /** First watermark in the folded text, and whether it is a generic (unknown site) domain */
    private fun findMarker(norm: String): Pair<MatchResult, Boolean>? {
        val maybeKnown = mayContainKnownSite(norm)
        if (!maybeKnown && !tldHintRegex.containsMatchIn(norm)) return null
        if (maybeKnown) findKnownMarker(norm)?.let { return it }
        for (m in genericDomainRegex.findAll(norm)) {
            // Only treat an unknown domain as a watermark if the sentence looks like one
            if (sentenceHasKeyword(norm, m)) return m to true
        }
        return null
    }

    private fun findKnownMarker(norm: String): Pair<MatchResult, Boolean>? {
        for (m in knownSiteRegex.findAll(norm)) {
            val hasTld = m.groups[1] != null || m.groups[2] != null
            // "fast novel" in normal prose should not match "fastnovel", but letter spaced
            // obfuscation like "f r e e w e b n o v e l" should
            val tokens = m.value.split(Regex("\\s+")).count { it.isNotEmpty() }
            if (hasTld || tokens == 1 || (tokens >= 4 && sentenceHasKeyword(norm, m))) {
                return m to false
            }
        }
        return null
    }

    /**
     * Removes watermarks from a single run of text.
     * Returns the cleaned text, which may be blank.
     * */
    fun removeWatermarks(input: String): String {
        var text = input
        var changed = false
        // Bounded loop, every iteration removes at least one char
        var iterations = 0
        while (iterations++ < 32) {
            val folded = foldWithMap(text)
            val norm = folded.text
            if (norm.isEmpty()) break

            val (match, isGeneric) = findMarker(norm) ?: break

            val origStart = folded.starts[match.range.first]
            val origEnd = folded.ends[match.range.last]

            val sStart = sentenceStart(text, origStart)
            val sEnd = sentenceEndIdx(text, origEnd)
            val sentence = text.substring(sStart, sEnd)
            val normSentence = normalize(sentence).replace(match.value, " ")

            val (cutStart, cutEnd) =
                if (isGeneric || (sentence.length <= MAX_WATERMARK_SENTENCE && watermarkKeywordRegex.containsMatchIn(normSentence))) {
                    sStart to sEnd
                } else {
                    origStart to origEnd
                }

            text = text.substring(0, cutStart) + text.substring(cutEnd)
            changed = true
        }
        if (!changed) return input
        val tidied = tidy(text)
        return if (hasLettersOrDigits(tidied)) tidied else ""
    }

    /** True if the whole line is a translator/editor credit */
    fun isCreditLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CREDIT_LINE) return false
        return creditStartRegex.containsMatchIn(normalize(trimmed))
    }

    /** Removes credit lines, or a trailing credit from a short line such as a chapter heading */
    fun removeCredits(input: String): String {
        if (!input.contains('\n')) return removeCreditsFromLine(input)
        return input.split('\n').mapNotNull { line ->
            val cleaned = removeCreditsFromLine(line)
            if (cleaned.isBlank() && line.isNotBlank()) null else cleaned
        }.joinToString("\n")
    }

    private fun removeCreditsFromLine(line: String): String {
        if (line.isBlank() || line.trim().length > MAX_CREDIT_LINE) return line
        if (isCreditLine(line)) return ""
        val folded = foldWithMap(line)
        val match = creditInlineRegex.find(folded.text) ?: return line
        val origStart = folded.starts[match.range.first]
        val before = line.substring(0, origStart).trimEnd { it.isWhitespace() || it in "-–—|:,;" }
        return if (hasLettersOrDigits(before)) before else ""
    }

    fun cleanText(text: String, removeCredits: Boolean = true, removeWatermarks: Boolean = true): String {
        var out = text
        if (removeWatermarks) out = removeWatermarks(out)
        if (removeCredits) out = removeCredits(out)
        return out
    }

    // ------------------------------------------------------------------------------------------
    // DOM cleaning
    // ------------------------------------------------------------------------------------------

    private val removableWhenEmpty = setOf(
        "p", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "u",
        "small", "sup", "sub", "font", "a", "center", "blockquote", "li", "section",
    )
    private val mediaTags = setOf("img", "svg", "image", "video", "audio", "hr", "table", "iframe")

    private fun isBlankText(node: Node?): Boolean = node is TextNode && node.isBlank

    private fun removeFollowingBr(node: Node) {
        var next = node.nextSibling()
        while (isBlankText(next)) next = next?.nextSibling()
        if (next is Element && next.normalName() == "br") next.remove()
    }

    private fun isEmptyElement(el: Element): Boolean {
        if (el.normalName() !in removableWhenEmpty) return false
        if (hasLettersOrDigits(el.text())) return false
        if (el.select(mediaTags.joinToString(",")).isNotEmpty()) return false
        return true
    }

    /**
     * Cleans a chapter in place.
     *
     * @param root the chapter body
     * */
    fun clean(root: Element, removeCredits: Boolean = true, removeWatermarks: Boolean = true) {
        if (!removeCredits && !removeWatermarks) return

        val removedParents = ArrayList<Element>()

        // 1. Whole block elements that only contain a watermark/credit, even if the text is
        //    split over multiple inline elements like <span>free</span><span>webnovel</span>
        for (el in root.select("p, div, h1, h2, h3, h4, h5, h6, li, blockquote, span").reversed()) {
            if (el.parent() == null) continue // already removed through an ancestor
            if (el.children().any { it.isBlock }) continue
            val text = el.text()
            if (!hasLettersOrDigits(text) || text.length > MAX_WATERMARK_SENTENCE) continue
            if (el.select(mediaTags.joinToString(",")).isNotEmpty()) continue
            val cleaned = cleanText(text, removeCredits, removeWatermarks)
            if (!hasLettersOrDigits(cleaned)) {
                removeFollowingBr(el)
                el.parent()?.let { removedParents.add(it) }
                el.remove()
            }
        }

        val touched = LinkedHashSet<Element>()

        // 2. Individual text nodes, this handles <br> separated text and inline watermarks
        val textNodes = ArrayList<TextNode>()
        root.traverse { node, _ -> if (node is TextNode && !node.isBlank) textNodes.add(node) }
        for (node in textNodes) {
            if (node.parent() == null) continue
            val original = node.wholeText
            val cleaned = cleanText(original, removeCredits, removeWatermarks)
            if (cleaned == original) continue
            node.parent()?.let { if (it is Element) touched.add(it) }
            if (!hasLettersOrDigits(cleaned)) {
                removeFollowingBr(node)
                node.remove()
            } else {
                // keep leading/trailing whitespace so words do not merge with siblings
                val lead = original.takeWhile { it.isWhitespace() }
                val trail = original.takeLastWhile { it.isWhitespace() }
                node.text(lead + cleaned.trim() + trail)
            }
        }

        // 3. Remove elements that became empty because of the cleaning, walking up the tree.
        //    Untouched empty elements are kept as they might be used for spacing.
        touched.addAll(removedParents)
        for (start in touched) {
            var el: Element? = start
            while (el != null && el !== root && el.parent() != null && isEmptyElement(el)) {
                val parent = el.parent()
                removeFollowingBr(el)
                el.remove()
                el = parent
            }
        }
    }
}
