package com.noiq.lingchat.voicewake

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination

/**
 * 把中文唤醒词转换成 KWS 模型要求的 keywords 行。
 *
 * 模型建模单元是「声母 + 带调韵母」两个 token，例如：
 *   小爱同学 → `x iǎo ài t óng x ué @小爱同学`
 * 该行的 token 必须逐一命中模型的 tokens.txt，否则永远无法触发。
 *
 * 规则要点（已用模型词表对 20902 个汉字做过全量校验）：
 * - y / w 视作声母（文 → `w én`，艺 → `y ì`）
 * - 零声母音节只输出韵母（爱 → `ài`，儿 → `ér`）
 * - j/q/x 后的 ü 按拼写写作 u（学 → `x ué`），n/l 后保持 ü（女 → `n ǚ`）
 * - 声调标在 a > o > e > i > u > ü 优先级最高的元音上；iu/ui 标在后一个
 */
object PinyinKeywords {
    private const val ONSET_ORDER = "zh,ch,sh,b,p,m,f,d,t,n,l,g,k,h,j,q,x,r,z,c,s,y,w"

    private val initials = ONSET_ORDER.split(",")

    private val marks = mapOf(
        'a' to "āáǎà",
        'o' to "ōóǒò",
        'e' to "ēéěè",
        'i' to "īíǐì",
        'u' to "ūúǔù",
        'ü' to "ǖǘǚǜ",
    )

    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITH_TONE_NUMBER
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    class UnsupportedPhraseException(val reason: String) : Exception(reason)

    /** 生成 keywords 行；无法映射时抛 [UnsupportedPhraseException]。 */
    fun buildKeywordLine(phrase: String): String {
        val trimmed = phrase.trim()
        if (trimmed.isEmpty()) throw UnsupportedPhraseException("empty_phrase")

        val tokens = mutableListOf<String>()
        for (ch in trimmed) {
            if (ch.isWhitespace()) continue
            val raw = try {
                PinyinHelper.toHanyuPinyinStringArray(ch, format)?.firstOrNull()
            } catch (_: BadHanyuPinyinOutputFormatCombination) {
                null
            }
            if (raw.isNullOrEmpty()) throw UnsupportedPhraseException("non_hanzi:$ch")
            tokens += syllableToTokens(raw)
        }
        if (tokens.isEmpty()) throw UnsupportedPhraseException("empty_phrase")
        return tokens.joinToString(" ") + " @$trimmed"
    }

    /** 返回第一个不在词表里的 token；全部命中返回 null。 */
    fun findUnmappedToken(line: String, knownTokens: Set<String>): String? =
        line.substringBefore('@').trim()
            .split(' ')
            .firstOrNull { it.isNotEmpty() && it !in knownTokens }

    /** 模型返回的 keyword 形如 `x iǎo ài t óng x ué @小爱同学`，取出可读文本。 */
    fun displayText(keyword: String): String = keyword.substringAfter('@', keyword).trim()

    private fun syllableToTokens(raw: String): List<String> {
        var tone = 0
        for (c in raw) {
            if (c.isDigit()) tone = c - '0'
        }
        val syllable = raw.filter { !it.isDigit() }.replace("u:", "ü").replace('v', 'ü')
        val initial = initials.firstOrNull { syllable.startsWith(it) } ?: ""
        val final = syllable.substring(initial.length)
        val marked = applyTone(final, tone)
        return if (initial.isEmpty()) listOf(marked) else listOf(initial, marked)
    }

    private fun applyTone(final: String, tone: Int): String {
        if (tone !in 1..4) return final

        var index = -1
        if (final == "iu" || final == "ui") {
            index = 1
        } else {
            for (vowel in charArrayOf('a', 'o', 'e')) {
                val found = final.indexOf(vowel)
                if (found >= 0) {
                    index = found
                    break
                }
            }
            if (index < 0) {
                for (i in final.indices.reversed()) {
                    if (marks.containsKey(final[i])) {
                        index = i
                        break
                    }
                }
            }
        }
        if (index < 0) return final

        val table = marks[final[index]] ?: return final
        return final.substring(0, index) + table[tone - 1] + final.substring(index + 1)
    }
}

