package com.soni08.morseai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * MorseVoiceAI 用の端末操作 AccessibilityService。
 * モールス信号や音声コマンドから受け取った操作指示を画面要素・システム操作へ反映する。
 */
class DeviceControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DeviceControlA11y"

        const val ACTION_CONTROL = "com.soni08.morseai.action.DEVICE_CONTROL"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TEXT = "text"

        const val CMD_NEXT = "NEXT"
        const val CMD_PREVIOUS = "PREVIOUS"
        const val CMD_CLICK = "CLICK"
        const val CMD_LONG_CLICK = "LONG_CLICK"
        const val CMD_IME_ENTER = "IME_ENTER"
        const val CMD_SET_TEXT = "SET_TEXT"
        const val CMD_SCROLL_FORWARD = "SCROLL_FORWARD"
        const val CMD_SCROLL_BACKWARD = "SCROLL_BACKWARD"
        const val CMD_BACK = "BACK"
        const val CMD_HOME = "HOME"
        const val CMD_RECENTS = "RECENTS"
        const val CMD_NOTIFICATIONS = "NOTIFICATIONS"

        @Volatile
        var instance: DeviceControlAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /**
         * AssistantForegroundService などからアクション文字列を実行するエントリーポイント。
         */
        fun executeAction(rawAction: String): Boolean {
            val service = instance ?: return false
            return service.performParsedAction(rawAction)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50L
        }

        Log.d(TAG, "DeviceControlAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 必要に応じてフォーカス変化や画面遷移を監視
    }

    override fun onInterrupt() {
        Log.w(TAG, "DeviceControlAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        Log.d(TAG, "DeviceControlAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONTROL) {
            val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
            val text = intent.getStringExtra(EXTRA_TEXT)
            executeCommand(command, text)
        }
        return START_STICKY
    }

    /**
     * AssistantForegroundService から渡されるアクション文字列を解釈して実行する。
     */
    private fun performParsedAction(rawAction: String): Boolean {
        return try {
            when {
                rawAction.startsWith("OPEN_URL:") -> {
                    val url = rawAction.removePrefix("OPEN_URL:").trim()
                    if (url.isEmpty()) return false
                    openExternalUri(url)
                }

                rawAction.startsWith("PLAY_MUSIC:") -> {
                    val payload = rawAction.removePrefix("PLAY_MUSIC:")
                    val ytUrl = payload.substringAfter("|", "").trim()
                    if (ytUrl.isEmpty()) return false
                    openExternalUri(ytUrl)
                }

                rawAction.startsWith("CHAT_SEND:") -> {
                    // 相手・メッセージの自動入力や送信は画面構成依存のため、必要に応じて実装を追加
                    Log.w(TAG, "CHAT_SEND is not yet automated: $rawAction")
                    false
                }

                rawAction == "ACTION_BACK" -> executeCommand(CMD_BACK)
                rawAction == "ACTION_HOME" -> executeCommand(CMD_HOME)
                rawAction == "ACTION_NOTIFICATIONS" -> executeCommand(CMD_NOTIFICATIONS)
                rawAction == "ACTION_RECENTS" -> executeCommand(CMD_RECENTS)
                rawAction == "ACTION_VOLUME_UP" -> adjustVolume(raise = true)
                rawAction == "ACTION_VOLUME_DOWN" -> adjustVolume(raise = false)

                else -> executeCommand(rawAction)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute action: $rawAction", e)
            false
        }
    }

    private fun openExternalUri(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        return true
    }

    private fun adjustVolume(raise: Boolean): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }

    /**
     * 外部（Activity / Service / Receiver）からコマンドを実行するエントリーポイント。
     */
    fun executeCommand(command: String, text: String? = null): Boolean {
        return when (command.uppercase()) {
            CMD_NEXT -> moveFocus(forward = true)
            CMD_PREVIOUS -> moveFocus(forward = false)
            CMD_CLICK -> performClickOnFocusedOrActiveNode()
            CMD_LONG_CLICK -> performLongClickOnFocusedOrActiveNode()
            CMD_IME_ENTER -> performImeEnter()
            CMD_SET_TEXT -> setTextOnFocusedNode(text.orEmpty())
            CMD_SCROLL_FORWARD -> performScroll(forward = true)
            CMD_SCROLL_BACKWARD -> performScroll(forward = false)
            CMD_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            CMD_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            CMD_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            CMD_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> {
                Log.w(TAG, "Unknown command: $command")
                false
            }
        }
    }

    private fun findTargetNode(requireEditable: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocused != null && (!requireEditable || inputFocused.isEditable)) {
            return inputFocused
        }

        val a11yFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (a11yFocused != null && (!requireEditable || a11yFocused.isEditable)) {
            return a11yFocused
        }

        if (requireEditable) {
            return findFirstEditableNode(root)
        }

        return root
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableNode(child)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun collectInteractiveNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isVisibleToUser && node.isEnabled && (node.isClickable || node.isEditable || node.isFocusable)) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectInteractiveNodes(node.getChild(i), out)
        }
    }

    private fun moveFocus(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectInteractiveNodes(root, candidates)
        if (candidates.isEmpty()) return false

        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        val currentIndex = if (current != null) {
            candidates.indexOfFirst { it == current }
        } else {
            -1
        }

        val nextIndex = when {
            currentIndex == -1 -> if (forward) 0 else candidates.lastIndex
            forward -> (currentIndex + 1) % candidates.size
            else -> if (currentIndex - 1 < 0) candidates.lastIndex else currentIndex - 1
        }

        val target = candidates[nextIndex]
        return target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) ||
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun performClickOnFocusedOrActiveNode(): Boolean {
        val root = rootInActiveWindow ?: return false
        var node: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            node = node.parent
        }
        return false
    }

    private fun performLongClickOnFocusedOrActiveNode(): Boolean {
        val root = rootInActiveWindow ?: return false
        var node: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        while (node != null) {
            if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return true
            }
            node = node.parent
        }
        return false
    }

    private fun performImeEnter(): Boolean {
        val target = findTargetNode(requireEditable = true) ?: findTargetNode(requireEditable = false) ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return true
            }
        }

        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun setTextOnFocusedNode(newText: String): Boolean {
        val target = findTargetNode(requireEditable = true) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun performScroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return performScrollRecursive(root, action)
    }

    private fun performScrollRecursive(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable && node.performAction(action)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (performScrollRecursive(child, action)) {
                return true
            }
        }
        return false
    }
}
