package com.soni08.morseai

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.soni08.morseai.service.AssistantForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etLocalAiUrl: EditText
    private lateinit var etLocalAiModel: EditText
    private lateinit var spAiMode: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val aiModes = listOf(
        "hybrid_gemini_first", // Gemini無料枠優先 -> 失敗時ローカルAI
        "hybrid_local_first",  // ローカルAI優先 -> 失敗時Gemini無料枠
        "gemini_only",         // Gemini無料枠のみ
        "local_only"           // ローカルAIのみ（完全オフライン可）
    )

    private val aiModeLabels = listOf(
        "ハイブリッド（無料枠Gemini優先 → ローカルAI）",
        "ハイブリッド（ローカルAI優先 → 無料枠Gemini）",
        "無料枠 Gemini API のみ（サーバー経由）",
        "ローカルAI のみ（端末内 / LAN）"
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micOk = grants[Manifest.permission.RECORD_AUDIO] == true
        if (micOk) {
            startAssistantService()
        } else {
            appendLog("マイク権限が必要です。")
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AssistantForegroundService.EXTRA_LOG_MSG) ?: return
            appendLog(msg)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServerUrl = findViewById(R.id.etServerUrl)
        etToken = findViewById(R.id.etToken)
        etLocalAiUrl = findViewById(R.id.etLocalAiUrl)
        etLocalAiModel = findViewById(R.id.etLocalAiModel)
        spAiMode = findViewById(R.id.spAiMode)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        spAiMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, aiModeLabels)

        val prefs = getSharedPreferences("morse_ai_prefs", MODE_PRIVATE)
        etServerUrl.setText(prefs.getString("server_url", ""))
        etToken.setText(prefs.getString("token", ""))
        etLocalAiUrl.setText(prefs.getString("local_ai_url", "http://127.0.0.1:11434/v1/chat/completions"))
        etLocalAiModel.setText(prefs.getString("local_ai_model", "gemma2:2b"))
        val savedMode = prefs.getString("ai_mode", "hybrid_gemini_first")
        val idx = aiModes.indexOf(savedMode).coerceAtLeast(0)
        spAiMode.setSelection(idx)

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            savePrefs()
            ensurePermissionsAndStart()
        }

        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            val stopIntent = Intent(this, AssistantForegroundService::class.java).apply {
                action = AssistantForegroundService.ACTION_STOP
            }
            startService(stopIntent)
            tvStatus.text = "状態: 停止中"
            appendLog("サービスを停止しました。")
        }

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val filter = IntentFilter(AssistantForegroundService.BROADCAST_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    private fun selectedMode(): String {
        val pos = spAiMode.selectedItemPosition.coerceIn(0, aiModes.lastIndex)
        return aiModes[pos]
    }

    private fun savePrefs() {
        getSharedPreferences("morse_ai_prefs", MODE_PRIVATE).edit()
            .putString("server_url", etServerUrl.text.toString().trim())
            .putString("token", etToken.text.toString().trim())
            .putString("local_ai_url", etLocalAiUrl.text.toString().trim())
            .putString("local_ai_model", etLocalAiModel.text.toString().trim())
            .putString("ai_mode", selectedMode())
            .apply()
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startAssistantService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAssistantService() {
        val intent = Intent(this, AssistantForegroundService::class.java).apply {
            putExtra(AssistantForegroundService.EXTRA_SERVER_URL, etServerUrl.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_TOKEN, etToken.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_LOCAL_AI_URL, etLocalAiUrl.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_LOCAL_AI_MODEL, etLocalAiModel.text.toString().trim())
            putExtra(AssistantForegroundService.EXTRA_AI_MODE, selectedMode())
        }
        ContextCompat.startForegroundService(this, intent)
        tvStatus.text = "状態: バックグラウンド稼働中（${selectedMode()}）"
        appendLog("常時サービスを開始しました。")
    }

    private fun appendLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLog.append("[$time] $line
")
    }

    override fun onDestroy() {
        unregisterReceiver(logReceiver)
        super.onDestroy()
    }
}
