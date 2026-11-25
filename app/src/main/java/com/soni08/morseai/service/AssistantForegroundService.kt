package com.soni08.morseai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.soni08.morseai.ai.LocalAiClient
import com.soni08.morseai.morse.ToneCountMorseDetector
import com.soni08.morseai.text.HiraganaNormalizer
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class PendingExecution(
    val action: String,
    val resultSummary: String,
    val originalCommandText: String
)

data class LocalCommandParseResult(
    val requiresConfirmation: Boolean,
    val action: String,
    val confirmPrompt: String,
    val resultSummary: String
)

class AssistantForegroundService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "morse_voice_ai_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.soni08.morseai.STOP"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_LOCAL_AI_URL = "local_ai_url"
        const val EXTRA_LOCAL_AI_MODEL = "local_ai_model"
        const val EXTRA_AI_MODE = "ai_mode"
        const val BROADCAST_LOG = "com.soni08.morseai.LOG"
        const val EXTRA_LOG_MSG = "log_msg"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var serverUrl: String = ""
    private var sharedToken: String = ""
    private var localAiUrl: String = ""
    private var localAiModel: String = "gemma2:2b"
    private var aiMode: String = "hybrid_gemini_first"

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var morseDetector: ToneCountMorseDetector? = null

    @Volatile
    private var isSpeaking = false
    @Volatile
    private var isStopping = false
    @Volatile
    private var pendingExecution: PendingExecution? = null

    private var onTtsDoneCallback: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: serverUrl
        sharedToken = intent?.getStringExtra(EXTRA_TOKEN) ?: sharedToken
        localAiUrl = intent?.getStringExtra(EXTRA_LOCAL_AI_URL) ?: localAiUrl
        localAiModel = intent?.getStringExtra(EXTRA_LOCAL_AI_MODEL) ?: localAiModel
        aiMode = intent?.getStringExtra(EXTRA_AI_MODE) ?: aiMode

        startForeground(NOTIF_ID, buildNotification("たいきちゅう（Gemini無料枠＋ローカルAI）"))
        startMorseDetector()
        startSpeechRecognitionLoop()

        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.JAPANESE
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    morseDetector?.paused = true
                    mainHandler.post { speechRecognizer?.stopListening() }
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    morseDetector?.paused = false
                    val cb = onTtsDoneCallback
                    onTtsDoneCallback = null
                    mainHandler.post {
                        cb?.invoke()
                        if (!isStopping && onTtsDoneCallback == null) {
                            mainHandler.postDelayed({ restartSpeechRecognition() }, 350L)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    morseDetector?.paused = false
                    onTtsDoneCallback = null
                    mainHandler.postDelayed({
                        if (!isStopping) restartSpeechRecognition()
                    }, 350L)
                }
            })
            ttsReady = true
        }
    }

    private fun startMorseDetector() {
        if (morseDetector != null) return
        morseDetector = ToneCountMorseDetector(
            onShortcutTriggered = { pattern, actionOrPackage ->
                if (!isSpeaking) {
                    val hiraCmd = HiraganaNormalizer.toHiragana(actionOrPackage)
                    sendLog("もーるすしょーとかっと: $pattern -> $hiraCmd")
                    handleRecognizedHiraganaInput(hiraCmd, "morse:$pattern")
                }
            },
            onToneBurstCompleted = { count, symbol ->
                sendLog("とんけんしゅつ: ${count}かい -> $symbol")
            },
            onPhraseCompleted = { seq, decoded ->
                if (!isSpeaking) {
                    val hiraDecoded = HiraganaNormalizer.toHiragana(decoded)
                    sendLog("もーるすかくてい: $seq -> $hiraDecoded")
                    handleRecognizedHiraganaInput(hiraDecoded, "morse:$seq")
                }
            }
        ).apply {
            start()
        }
    }

    private fun startSpeechRecognitionLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendLog("おんせいにんしきがつかえません")
            return
        }
        mainHandler.post {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            if (!isStopping && !isSpeaking && onTtsDoneCallback == null) {
                                mainHandler.postDelayed({ restartSpeechRecognition() }, 800L)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val rawRecognized = matches?.firstOrNull()?.trim()
                            if (!rawRecognized.isNullOrEmpty() && !isSpeaking) {
                                val hiraText = HiraganaNormalizer.toHiragana(rawRecognized)
                                sendLog("おんせいにんしき（ひらがな）: $hiraText")
                                handleRecognizedHiraganaInput(hiraText, rawRecognized)
                            }
                            if (!isStopping && !isSpeaking && onTtsDoneCallback == null) {
                                mainHandler.postDelayed({ restartSpeechRecognition() }, 400L)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
            restartSpeechRecognition()
        }
    }

    private fun handleRecognizedHiraganaInput(hiraText: String, rawText: String) {
        val pending = pendingExecution
        if (pending != null) {
            if (HiraganaNormalizer.isApproval(hiraText)) {
                pendingExecution = null
                sendLog("きょかをかくにん: じっこうします")
                executePendingAction(pending)
                return
            }
            if (HiraganaNormalizer.isCancellation(hiraText)) {
                pendingExecution = null
                sendLog("ちゅうしされました")
                speakHiragana("じっこうを ちゅうししました")
                return
            }
            speakHiragana("おっけい か きょか と いってください")
            return
        }

        // local_only モードまたはサーバー未設定時は端末側パーサー＋ローカルAIで完結
        if (aiMode == "local_only" || serverUrl.isBlank()) {
            val localCmd = parseCommandLocally(hiraText)
            if (localCmd != null) {
                pendingExecution = PendingExecution(
                    action = localCmd.action,
                    resultSummary = localCmd.resultSummary,
                    originalCommandText = hiraText
                )
                sendLog("ろーかるふくしょう: ${localCmd.confirmPrompt}")
                speakHiragana(localCmd.confirmPrompt)
                return
            }
            askLocalAiOnly(hiraText)
            return
        }

        routeRequestHybrid(hiraText, rawText)
    }

    private fun parseCommandLocally(hiraText: String): LocalCommandParseResult? {
        // 1. Xリアルタイム検索
        val xMatch = Regex("^(?:えっくすで|りあるたいむけんさく(?:で)?|やふーりあるたいむ(?:で)?)\s*(.+?)(?:をけんさく)?$").find(hiraText)
        if (xMatch != null) {
            val q = xMatch.groupValues[1].trim()
            val url = "https://search.yahoo.co.jp/realtime/search?p=" + Uri.encode(q)
            return LocalCommandParseResult(
                requiresConfirmation = true,
                action = "OPEN_URL:$url",
                confirmPrompt = "えっくすのりあるたいむけんさくで、$q をけんさくします。おっけいかきょかとってください",
                resultSummary = "えっくすのけんさくをじっこうしました"
            )
        }

        // 2. Google検索
        val gMatch1 = Regex("^(?:ぐーぐるで|けんさく(?:して)?)\s*(.+)$").find(hiraText)
        val gMatch2 = Regex("^(.+?)(?:をぐーぐるけんさく|をけんさく(?:して)?)$").find(hiraText)
        val gQuery = (gMatch1 ?: gMatch2)?.groupValues?.getOrNull(1)?.trim()
        if (!gQuery.isNullOrEmpty()) {
            val url = "https://www.google.com/search?q=" + Uri.encode(gQuery)
            return LocalCommandParseResult(
                requiresConfirmation = true,
                action = "OPEN_URL:$url",
                confirmPrompt = "ぐーぐるで、$gQuery をけんさくします。おっけいかきょかとってください",
                resultSummary = "ぐーぐるけんさくをじっこうしました"
            )
        }

        // 3. 音楽再生
        val mMatch1 = Regex("^(?:おんがくけんさく|きょくけんさく|さいせい(?:して)?)\s*(.+)$").find(hiraText)
        val mMatch2 = Regex("^(.+?)(?:のきょくをさいせい|をさいせい(?:して)?|のおんがくをさいせい)$").find(hiraText)
        val song = (mMatch1 ?: mMatch2)?.groupValues?.getOrNull(1)?.trim()
        if (!song.isNullOrEmpty()) {
            val ytUrl = "https://music.youtube.com/search?q=" + Uri.encode(song)
            return LocalCommandParseResult(
                requiresConfirmation = true,
                action = "PLAY_MUSIC:$song|$ytUrl",
                confirmPrompt = "おんがくの、$song をけんさくしてさいせいします。おっけいかきょかとってください",
                resultSummary = "おんがくのさいせいをじっこうしました"
            )
        }

        // 4. LINE送信
        val chatMatch = Regex("^(?:らいんで|ちゃっとで)?\s*(.+?)に(.+?)(?:とおくる|とそうしん|をおくる|をそうしん)$").find(hiraText)
        if (chatMatch != null) {
            val target = chatMatch.groupValues[1].trim()
            val msg = chatMatch.groupValues[2].trim()
            return LocalCommandParseResult(
                requiresConfirmation = true,
                action = "CHAT_SEND:$target||$msg",
                confirmPrompt = "らいんで、$target さんに、$msg とそうしんします。おっけいかきょかとってください",
                resultSummary = "ちゃっとのそうしんをじっこうしました"
            )
        }

        // 5. 基本操作
        val basicMap = mapOf(
            "もどる" to "ACTION_BACK",
            "ほーむ" to "ACTION_HOME",
            "つうち" to "ACTION_NOTIFICATIONS",
            "りれき" to "ACTION_RECENTS",
            "おんりょうあっぷ" to "ACTION_VOLUME_UP",
            "おんりょうだうん" to "ACTION_VOLUME_DOWN"
        )
        val act = basicMap[hiraText]
        if (act != null) {
            return LocalCommandParseResult(
                requiresConfirmation = true,
                action = act,
                confirmPrompt = "$hiraText をじっこうします。おっけいかきょかとってください",
                resultSummary = "$hiraText をじっこうしました"
            )
        }
        return null
    }

    private fun askLocalAiOnly(hiraText: String) {
        networkExecutor.execute {
            val localReply = LocalAiClient.generateHiraganaReply(localAiUrl, localAiModel, hiraText)
            if (!localReply.isNullOrBlank()) {
                val hiraReply = HiraganaNormalizer.toHiragana(localReply)
                sendLog("ろーかるえーあいおうとう: $hiraReply")
                speakHiragana(hiraReply)
            } else {
                sendLog("ろーかるえーあいせつぞくしっぱい")
                speakHiragana("ろーかるえーあいに せつぞくできませんでした")
            }
        }
    }

    private fun routeRequestHybrid(hiraText: String, rawText: String) {
        // コマンド類は端末側でも即時判定可能にしつつ、通常会話はモードに応じて振り分け
        val localCmd = parseCommandLocally(hiraText)
        if (localCmd != null) {
            pendingExecution = PendingExecution(
                action = localCmd.action,
                resultSummary = localCmd.resultSummary,
                originalCommandText = hiraText
            )
            sendLog("ふくしょう・かくにんまち: ${localCmd.confirmPrompt}")
            speakHiragana(localCmd.confirmPrompt)
            return
        }

        networkExecutor.execute {
            if (aiMode == "hybrid_local_first") {
                val localReply = LocalAiClient.generateHiraganaReply(localAiUrl, localAiModel, hiraText)
                if (!localReply.isNullOrBlank()) {
                    val hiraReply = HiraganaNormalizer.toHiragana(localReply)
                    sendLog("ろーかるえーあいおうとう: $hiraReply")
                    speakHiragana(hiraReply)
                    return@execute
                }
                sendLog("ろーかるえーあいふか -> むりょうわくGeminiへきりかえ")
            }

            // サーバー経由（無料枠 Gemini API ＋ サーバー側フォールバック）
            val serverOk = sendToServerSync("command", hiraText, rawText)
            if (!serverOk && aiMode == "hybrid_gemini_first") {
                sendLog("Geminiむりょうわく/さーばーふか -> ろーかるえーあいへきりかえ")
                val fallbackLocal = LocalAiClient.generateHiraganaReply(localAiUrl, localAiModel, hiraText)
                if (!fallbackLocal.isNullOrBlank()) {
                    val hiraReply = HiraganaNormalizer.toHiragana(fallbackLocal)
                    sendLog("ろーかるえーあいおうとう: $hiraReply")
                    speakHiragana(hiraReply)
                    return@execute
                }
                speakHiragana("つうしんに しっぱいしました")
            }
        }
    }

    private fun executePendingAction(pending: PendingExecution) {
        val ok = DeviceControlAccessibilityService.executeAction(pending.action)
        if (!ok) {
            val failMsg = "じっこうに しっぱいしました。ゆーざーほじょなどを かくにんしてください"
            sendLog("じっこうしっぱい: ${pending.action}")
            speakHiragana(failMsg)
            return
        }

        val resultMsg = if (pending.resultSummary.isNotBlank()) {
            pending.resultSummary
        } else {
            "じっこうに せいこうしました"
        }
        sendLog("じっこうけっかをよみあげ: $resultMsg")

        onTtsDoneCallback = {
            requestPostResultDetails(pending)
        }
        speakHiragana(resultMsg)
    }

    private fun requestPostResultDetails(pending: PendingExecution) {
        networkExecutor.execute {
            try {
                val detailText = when {
                    pending.action.startsWith("OPEN_URL:") -> {
                        val queryLabel = HiraganaNormalizer.toHiragana(pending.originalCommandText)
                        val aiSummary = fetchPostResultSummaryHybrid(queryLabel)
                        if (aiSummary.isNotBlank()) {
                            "けんさくがめんを ひらきました。$aiSummary"
                        } else {
                            "けんさくがめんを ひらきました"
                        }
                    }
                    pending.action.startsWith("PLAY_MUSIC:") -> {
                        "おんがくの さいせいがめんを ひらきました"
                    }
                    pending.action.startsWith("CHAT_SEND:") -> {
                        "めっせーじの にゅうりょくと そうしんしょりを おこないました"
                    }
                    else -> {
                        "そうさが かんりょうしました"
                    }
                }
                val hiraDetail = HiraganaNormalizer.toHiragana(detailText)
                sendLog("けんさくけっか・しょうさいをよみあげ: $hiraDetail")
                speakHiragana(hiraDetail)
            } catch (_: Exception) {
                speakHiragana("しょりが かんりょうしました")
            }
        }
    }

    private fun fetchPostResultSummaryHybrid(hiraContext: String): String {
        val prompt = "つぎのじっこうけっかのないようやけんさくけっかのぽいんとを、ひらがなちゅうしんで、おんせいごうせいでよみあげやすいようにみじかくしぜんなにほんごでおしえてください: $hiraContext"
        if (aiMode == "local_only" || aiMode == "hybrid_local_first") {
            val localAns = LocalAiClient.generateHiraganaReply(localAiUrl, localAiModel, prompt)
            if (!localAns.isNullOrBlank()) return localAns
        }
        if (serverUrl.isNotBlank()) {
            val srvAns = fetchPostResultSummaryFromServer(hiraContext)
            if (srvAns.isNotBlank()) return srvAns
        }
        return LocalAiClient.generateHiraganaReply(localAiUrl, localAiModel, prompt) ?: ""
    }

    private fun fetchPostResultSummaryFromServer(hiraContext: String): String {
        return try {
            val url = URL(serverUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val payload = JSONObject().apply {
                put("token", sharedToken)
                put("stage", "post_result_summary")
                put("preferred_ai", aiMode)
                put("input_type", "voice")
                put("text", hiraContext)
                put("raw_text", hiraContext)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            JSONObject(body).optString("reply", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun restartSpeechRecognition() {
        if (isStopping || isSpeaking) return
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            mainHandler.postDelayed({ restartSpeechRecognition() }, 1000L)
        }
    }

    private fun sendToServerSync(stage: String, hiraText: String, rawText: String): Boolean {
        if (serverUrl.isBlank()) return false
        return try {
            val url = URL(serverUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val payload = JSONObject().apply {
                put("token", sharedToken)
                put("stage", stage)
                put("preferred_ai", aiMode)
                put("input_type", if (rawText.startsWith("morse:")) "morse" else "voice")
                put("text", hiraText)
                put("raw_text", rawText)
                put("morse_pattern", if (rawText.startsWith("morse:")) rawText.removePrefix("morse:") else "")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code !in 200..299 || body.isBlank()) return false

            val json = JSONObject(body)
            val requiresConfirmation = json.optBoolean("requires_confirmation", false)
            val confirmPrompt = HiraganaNormalizer.toHiragana(json.optString("confirm_prompt", ""))
            val action = json.optString("action", "")
            val resultSummary = HiraganaNormalizer.toHiragana(json.optString("result_summary", ""))
            val reply = HiraganaNormalizer.toHiragana(json.optString("reply", ""))
            val usedAi = json.optString("used_ai", "gemini")

            if (requiresConfirmation && action.isNotEmpty() && action != "null") {
                pendingExecution = PendingExecution(
                    action = action,
                    resultSummary = resultSummary.ifBlank { "じっこうに せいこうしました" },
                    originalCommandText = hiraText
                )
                val promptToSpeak = confirmPrompt.ifBlank { "$hiraText をじっこうします。おっけいかきょかとってください" }
                sendLog("ふくしょう・かくにんまち: $promptToSpeak")
                speakHiragana(promptToSpeak)
                true
            } else if (reply.isNotEmpty() && usedAi != "none") {
                sendLog("えーあいおうとう（$usedAi）: $reply")
                speakHiragana(reply)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun speakHiragana(text: String) {
        if (!ttsReady) return
        val cleanHiragana = HiraganaNormalizer.toHiragana(text)
        if (cleanHiragana.isBlank()) return
        mainHandler.post {
            val id = UUID.randomUUID().toString()
            tts?.speak(cleanHiragana, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    private fun sendLog(msg: String) {
        val intent = Intent(BROADCAST_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOG_MSG, msg)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Morse & Voice AI Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("もーるす＆おんせい えーあい かどうちゅう")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isStopping = true
        onTtsDoneCallback = null
        morseDetector?.stop()
        morseDetector = null
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
