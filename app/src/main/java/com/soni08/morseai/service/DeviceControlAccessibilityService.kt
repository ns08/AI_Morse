package com.soni08.morseai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DeviceControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DeviceControlAccessibilityService? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        private val appAliasMap = mapOf(
            "line" to "jp.naver.line.android",
            "ライン" to "jp.naver.line.android",
            "らいん" to "jp.naver.line.android",
            "設定" to "com.android.settings",
            "せってい" to "com.android.settings",
            "カメラ" to "com.android.camera2",
            "ブラウザ" to "com.android.chrome",
            "chrome" to "com.android.chrome",
            "マップ" to "com.google.android.apps.maps",
            "youtube" to "com.google.android.youtube",
            "ゆーちゅーぶ" to "com.google.android.youtube"
        )

        fun executeAction(actionCode: String): Boolean {
            val svc = instance ?: return false

            if (actionCode.startsWith("OPEN_URL:")) {
                val url = actionCode.removePrefix("OPEN_URL:").trim()
                return openUrl(url)
            }

            if (actionCode.startsWith("PLAY_MUSIC:")) {
                val payload = actionCode.removePrefix("PLAY_MUSIC:")
                val parts = payload.split("|", limit = 2)
                val query = parts.getOrElse(0) { "" }.trim()
                val fallbackUrl = parts.getOrElse(1) { "" }.trim()
                return searchAndPlayMusic(query, fallbackUrl)
            }

            if (actionCode.startsWith("CHAT_SEND:")) {
                val payload = actionCode.removePrefix("CHAT_SEND:")
                val parts = payload.split("||", limit = 2)
                val target = parts.getOrElse(0) { "" }.trim()
                val message = parts.getOrElse(1) { "" }.trim()
                return svc.performChatInputAndSend(target, message)
            }

            if (actionCode.startsWith("TYPE_AND_SEND:")) {
                val message = actionCode.removePrefix("TYPE_AND_SEND:")
                return svc.typeIntoFocusedOrFirstEditTextAndSend(message)
            }

            if (actionCode.startsWith("LAUNCH_PKG:")) {
                val pkg = actionCode.removePrefix("LAUNCH_PKG:").trim()
                return launchAppByPackage(pkg)
            }

            if (actionCode.startsWith("LAUNCH_APP:")) {
                val name = actionCode.removePrefix("LAUNCH_APP:").trim()
                return launchAppByNameOrPackage(name)
            }

            return when (actionCode) {
                "ACTION_BACK" -> svc.performGlobalAction(GLOBAL_ACTION_BACK)
                "ACTION_HOME" -> svc.performGlobalAction(GLOBAL_ACTION_HOME)
                "ACTION_NOTIFICATIONS" -> svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "ACTION_QUICK_SETTINGS" -> svc.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                "ACTION_RECENTS" -> svc.performGlobalAction(GLOBAL_ACTION_RECENTS)
                "ACTION_LOCK_SCREEN" -> svc.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                "ACTION_SCROLL_DOWN" -> svc.performVerticalScroll(scrollDown = true)
                "ACTION_SCROLL_UP" -> svc.performVerticalScroll(scrollDown = false)
                "ACTION_VOLUME_UP" -> {
                    val am = svc.getSystemService(AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    true
                }
                "ACTION_VOLUME_DOWN" -> {
                    val am = svc.getSystemService(AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                    true
                }
                else -> false
            }
        }

        fun openUrl(url: String): Boolean {
            val svc = instance ?: return false
            if (url.isBlank()) return false
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                svc.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun searchAndPlayMusic(query: String, fallbackUrl: String): Boolean {
            val svc = instance ?: return false
            if (query.isNotBlank()) {
                try {
                    val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                        putExtra(android.app.SearchManager.QUERY, query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (playIntent.resolveActivity(svc.packageManager) != null) {
                        svc.startActivity(playIntent)
                        return true
                    }
                } catch (_: Exception) {
                }
            }
            if (fallbackUrl.isNotBlank()) {
                return openUrl(fallbackUrl)
            }
            return openUrl("https://music.youtube.com/search?q=" + Uri.encode(query))
        }

        fun launchAppByPackage(packageName: String): Boolean {
            val svc = instance ?: return false
            if (packageName.isBlank()) return false
            val intent = svc.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivity(intent)
            return true
        }

        fun launchAppByNameOrPackage(nameOrPkg: String): Boolean {
            val normalized = nameOrPkg.trim().lowercase()
            val mappedPkg = appAliasMap[normalized] ?: appAliasMap[nameOrPkg.trim()]
            if (mappedPkg != null && launchAppByPackage(mappedPkg)) {
                return true
            }
            return launchAppByPackage(nameOrPkg.trim())
        }
    }

    private fun performChatInputAndSend(target: String, message: String): Boolean {
        if (message.isBlank()) return false
        val linePkg = "jp.naver.line.android"
        val currentPkg = rootInActiveWindow?.packageName?.toString()
        if (currentPkg != linePkg) {
            launchAppByPackage(linePkg)
            mainHandler.postDelayed({
                if (target.isNotBlank()) {
                    clickNodeByText(rootInActiveWindow, target)
                }
                mainHandler.postDelayed({
                    typeIntoFocusedOrFirstEditTextAndSend(message)
                }, 900L)
            }, 1300L)
            return true
        }

        if (target.isNotBlank()) {
            clickNodeByText(rootInActiveWindow, target)
            mainHandler.postDelayed({
                typeIntoFocusedOrFirstEditTextAndSend(message)
            }, 600L)
            return true
        }
        return typeIntoFocusedOrFirstEditTextAndSend(message)
    }

    private fun typeIntoFocusedOrFirstEditTextAndSend(message: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editNode = findFocusedEditText(root) ?: findFirstEditText(root) ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        val setOk = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!setOk) return false

        mainHandler.postDelayed({
            val currentRoot = rootInActiveWindow
            val clickedSend = clickSendButton(currentRoot)
            if (!clickedSend) {
                editNode.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
            }
        }, 350L)
        return true
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findFocusedEditText(child)
            if (res != null) return res
        }
        return null
    }

    private fun findFirstEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findFirstEditText(child)
            if (res != null) return res
        }
        return null
    }

    private fun clickNodeByText(root: AccessibilityNodeInfo?, query: String): Boolean {
        if (root == null || query.isBlank()) return false
        val matches = root.findAccessibilityNodeInfosByText(query)
        for (n in matches) {
            var target: AccessibilityNodeInfo? = n
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }
        return false
    }

    private fun clickSendButton(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val keywords = listOf("送信", "Send", "send")
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            for (n in nodes) {
                var target: AccessibilityNodeInfo? = n
                while (target != null && !target.isClickable) {
                    target = target.parent
                }
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }
        return findAndClickContentDescSend(root)
    }

    private fun findAndClickContentDescSend(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (node.isClickable && (desc.contains("send") || desc.contains("送信"))) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickContentDescSend(child)) return true
        }
        return false
    }

    private fun performVerticalScroll(scrollDown: Boolean): Boolean {
        val metrics: DisplayMetrics = resources.displayMetrics
        val centerX = metrics.widthPixels * 0.5f
        val startY = if (scrollDown) metrics.heightPixels * 0.72f else metrics.heightPixels * 0.28f
        val endY = if (scrollDown) metrics.heightPixels * 0.28f else metrics.heightPixels * 0.72f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 260L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }
}
