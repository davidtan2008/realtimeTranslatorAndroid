package com.duiyi.android.debug

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.duiyi.android.domain.AppLanguage
import com.duiyi.android.domain.Translating
import com.duiyi.android.services.DuiyiLog
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TranslationSelfTestActivity : ComponentActivity() {
    @Inject lateinit var translator: Translating

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            text = "正在自检 TranslateGemma…"
            textSize = 18f
            setPadding(48, 96, 48, 48)
        }
        setContentView(view)
        lifecycleScope.launch {
            val out = File(filesDir, "translate-selftest.txt")
            val lines = mutableListOf<String>()
            fun log(message: String) {
                lines += message
                DuiyiLog.i("selftest $message")
                out.writeText(lines.joinToString("\n"))
                view.text = lines.joinToString("\n")
            }
            try {
                log("start")
                val prepareMs = measureTimeMillis {
                    translator.prepare(
                        listOf(
                            AppLanguage.English to AppLanguage.ZhHans,
                            AppLanguage.ZhHans to AppLanguage.English,
                        ),
                    ) {}
                }
                log("prepared ${prepareMs}ms")
                val en2zh: String
                val enMs = measureTimeMillis {
                    en2zh = translator.translate(
                        AppLanguage.English,
                        AppLanguage.ZhHans,
                        "How are you.",
                        "",
                    )
                }
                log("en2zh ${enMs}ms '$en2zh'")
                val en2zhHit: String
                val enHitMs = measureTimeMillis {
                    en2zhHit = translator.translate(
                        AppLanguage.English,
                        AppLanguage.ZhHans,
                        "It's very cute.",
                        "There are a lot of trees in the park.",
                    )
                }
                log("en2zh_hit ${enHitMs}ms '$en2zhHit'")
                val zh2en: String
                val zhMs = measureTimeMillis {
                    zh2en = translator.translate(
                        AppLanguage.ZhHans,
                        AppLanguage.English,
                        "这个也不对呀。",
                        "",
                    )
                }
                log("zh2en ${zhMs}ms '$zh2en'")
                val enHasHan = en2zh.any { it in '\u4e00'..'\u9fff' }
                val zhHasLatin = zh2en.any { it.isLetter() && it.code < 0x80 }
                val leakedContext = en2zhHit.contains("树") || en2zhHit.contains("公园") ||
                    en2zhHit.contains("背景") || en2zhHit.contains("语境")
                val cuteHasHan = en2zhHit.any { it in '\u4e00'..'\u9fff' }
                val pass = enHasHan && zhHasLatin && cuteHasHan && !leakedContext
                log("pass=$pass enHasHan=$enHasHan zhHasLatin=$zhHasLatin leakedContext=$leakedContext hitMs=$enHitMs")
            } catch (error: Throwable) {
                log("error ${error.javaClass.simpleName}: ${error.message}")
                log(error.stackTraceToString().take(1500))
            }
        }
    }
}
