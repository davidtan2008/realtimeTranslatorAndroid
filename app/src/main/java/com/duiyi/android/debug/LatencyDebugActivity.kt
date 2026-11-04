package com.duiyi.android.debug

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.duiyi.android.services.ComputeGate
import com.duiyi.android.services.LatencyProbe
import com.duiyi.android.services.LatencyReport
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Reads out where a real conversation's time went.
 *
 * The self-test times Gemma in isolation — no microphone, no VAD, no SenseVoice competing
 * for cores. This screen shows the same stages measured in the live path, which is the only
 * way to tell whether "the translation is slow" is actually Gemma or the pipeline around it.
 *
 * Launch: adb shell am start -n com.duiyi.android/.debug.LatencyDebugActivity
 */
@AndroidEntryPoint
class LatencyDebugActivity : ComponentActivity() {
    @Inject lateinit var probe: LatencyProbe
    @Inject lateinit var gate: ComputeGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Switchable so one conversation can be measured with partials skipping (the
        // default) and another with them competing, giving a direct comparison.
        val skipToggle = TextView(this).apply {
            text = skipLabel()
            textSize = 16f
            setPadding(40, 24, 40, 8)
            setOnClickListener {
                gate.skipPartialsWhileTranslating = !gate.skipPartialsWhileTranslating
                text = skipLabel()
                probe.reset()
            }
        }
        val body = TextView(this).apply {
            textSize = 13f
            setPadding(40, 40, 40, 40)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val clear = TextView(this).apply {
            text = "清空记录"
            textSize = 16f
            setPadding(40, 24, 40, 24)
            setOnClickListener {
                probe.reset()
                body.text = "cleared"
            }
        }
        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@LatencyDebugActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(skipToggle)
                        addView(clear)
                        addView(body)
                    },
                )
            },
        )
        lifecycleScope.launch {
            probe.report.collect { body.text = render(it) }
        }
    }

    private fun skipLabel(): String = if (gate.skipPartialsWhileTranslating) {
        "生成期间跳过 partial 解码：开（点一下关掉再测一轮对比）"
    } else {
        "生成期间跳过 partial 解码：关（partial 会和 Gemma 抢核）"
    }

    private fun render(report: LatencyReport): String {
        if (report.turns.isEmpty()) {
            return "还没有数据。\n\n开一个面对面任务，说几句中英，再回到这里。\n" +
                "每次翻译结束都会写入一行。\n\n原始记录：files/latency-probe.txt"
        }
        val out = StringBuilder()
        out.append("中位数（共 ${report.turns.size} 轮）\n")
        out.append("─────────────────────────────\n")
        report.medians.forEach { (label, ms) ->
            out.append(pad(label)).append("  ").append(ms).append(" ms\n")
        }
        out.append('\n')
        out.append("抢核：partial 解码与生成重叠 ${report.overlappedPartials} 次")
        if (report.skippedPartials > 0) {
            out.append("，已跳过 ${report.skippedPartials} 次")
        }
        out.append("\n\n")
        out.append("最近几轮\n")
        out.append("─────────────────────────────\n")
        out.append("seq  端点  ASR   持有  排队  首字  生成   总计  RTF  重叠/跳过\n")
        report.turns.take(12).forEach { turn ->
            out.append(pad(turn.seq.toString(), 4))
                .append(pad(ms(turn.speechToEndpointMs), 6))
                .append(pad(ms(turn.finalDecodeMs), 6))
                .append(pad(ms(turn.joinerHoldMs), 6))
                .append(pad(ms(turn.queueWaitMs), 6))
                .append(pad(ms(turn.firstTokenMs), 6))
                .append(pad(ms(turn.genMs), 7))
                .append(pad(ms(turn.speechToDoneMs), 7))
                .append(pad(String.format("%.1f", turn.finalRtf), 6))
                .append("  ")
                .append(turn.partialDecodesDuringGen)
                .append('/')
                .append(turn.partialDecodesSkipped)
                .append('\n')
        }
        out.append("\n单位 ms。“总计”= 从最后一个语音块到译文上屏。\n")
        out.append("原始记录：files/latency-probe.txt")
        return out.toString()
    }

    private fun pad(value: String, width: Int): String =
        if (value.length >= width) value else " ".repeat(width - value.length) + value

    private fun pad(label: String): String = pad(label, 18)

    private fun ms(value: Long): String = if (value < 0L) "—" else value.toString()
}
