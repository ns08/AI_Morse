package com.soni08.morseai.text

import java.text.Normalizer

object HiraganaNormalizer {

    private val kanjiToHiraganaMap = mapOf(
        "検索" to "けんさく",
        "検索する" to "けんさくする",
        "検索して" to "けんさくして",
        "送信" to "そうしん",
        "送信する" to "そうしんする",
        "送信して" to "そうしんして",
        "送る" to "おくる",
        "送って" to "おくって",
        "再生" to "さいせい",
        "再生する" to "さいせいする",
        "再生して" to "さいせいして",
        "音楽" to "おんがく",
        "曲" to "きょく",
        "戻る" to "もどる",
        "ホーム" to "ほーむ",
        "通知" to "つうち",
        "履歴" to "りれき",
        "音量" to "おんりょう",
        "設定" to "せってい",
        "許可" to "きょか",
        "実行" to "じっこう",
        "中止" to "ちゅうし",
        "キャンセル" to "きゃんせる",
        "ライン" to "らいん",
        "グーグル" to "ぐーぐる",
        "エックス" to "えっくす",
        "リアルタイム" to "りあるたいむ",
        "ヤフー" to "やふー",
        "ユーチューブ" to "ゆーちゅーぶ"
    )

    private val variantToHiraganaMap = mapOf(
        "おっけー" to "おっけい",
        "おーけー" to "おっけい",
        "おーけい" to "おっけい",
        "りょうかい" to "りょうかい",
        "いいよ" to "いいよ",
        "だいじょうぶ" to "だいじょうぶ",
        "やめる" to "やめる",
        "やめて" to "やめて",
        "だめ" to "だめ",
        "ちがう" to "ちがう",
        "らいん" to "らいん",
        "ぐーぐる" to "ぐーぐる",
        "えっくす" to "えっくす",
        "ゆーちゅーぶ" to "ゆーちゅーぶ",
        "やふー" to "やふー"
    )

    fun toHiragana(input: String): String {
        if (input.isBlank()) return ""
        val nfkc = Normalizer.normalize(input.trim(), Normalizer.Form.NFKC)
        val kanaConverted = katakanaToHiragana(nfkc)

        var result = kanaConverted.lowercase()
        val sortedKanji = kanjiToHiraganaMap.entries.sortedByDescending { it.key.length }
        for ((k, v) in sortedKanji) {
            val kHira = katakanaToHiragana(Normalizer.normalize(k, Normalizer.Form.NFKC)).lowercase()
            if (result.contains(kHira)) {
                result = result.replace(kHira, v)
            }
        }

        val sortedVariants = variantToHiraganaMap.entries.sortedByDescending { it.key.length }
        for ((k, v) in sortedVariants) {
            val kHira = katakanaToHiragana(Normalizer.normalize(k, Normalizer.Form.NFKC)).lowercase()
            if (result.contains(kHira)) {
                result = result.replace(kHira, v)
            }
        }

        return result.replace(Regex("\\s+"), " ").trim()
    }

    fun isApproval(input: String): Boolean {
        val h = toHiragana(input).replace("。", "").replace("！", "").replace("!", "").trim()
        val approvals = setOf(
            "おっけい", "おっけー", "きょか", "きょかします", "きょかする",
            "じっこう", "じっこうして", "じっこうします", "はい", "うん",
            "いいよ", "だいじょうぶ", "りょうかい", "おーけー"
        )
        if (approvals.contains(h)) return true
        return h.contains("おっけい") || h.contains("きょか") || h.contains("じっこうして") || h.contains("じっこうします")
    }

    fun isCancellation(input: String): Boolean {
        val h = toHiragana(input).replace("。", "").replace("！", "").replace("!", "").trim()
        val cancels = setOf(
            "だめ", "ちがう", "やめる", "やめて", "やめます",
            "ちゅうし", "きゃんせる", "いいえ", "ううん"
        )
        if (cancels.contains(h)) return true
        return h.contains("やめる") || h.contains("やめて") || h.contains("ちゅうし") || h.contains("きゃんせる")
    }

    private fun katakanaToHiragana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            if (code in 0x30A1..0x30F6) {
                sb.append((code - 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
