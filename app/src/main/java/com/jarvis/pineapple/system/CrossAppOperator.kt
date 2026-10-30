package com.jarvis.pineapple.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.pineapple.accessibility.JarvisAccessibilityService

/**
 * 跨应用操作（#57）：通过辅助功能服务操作其他 App
 * 白名单机制：仅在用户授权的 App 内执行操作
 */
class CrossAppOperator(private val context: Context) {

    private val whitelist = mutableSetOf<String>()

    /** 添加应用到白名单 */
    fun addToWhitelist(packageName: String) {
        whitelist.add(packageName)
    }

    /** 从白名单移除 */
    fun removeFromWhitelist(packageName: String) {
        whitelist.remove(packageName)
    }

    /** 检查应用是否在白名单中 */
    fun isWhitelisted(packageName: String): Boolean {
        return whitelist.contains(packageName)
    }

    /** 获取白名单 */
    fun getWhitelist(): Set<String> = whitelist.toSet()

    /**
     * 在目标 App 中执行点击操作
     * @param packageName 目标应用包名
     * @param text 要点击的文本
     * @return 是否成功
     */
    fun clickText(packageName: String, text: String): Boolean {
        if (!isWhitelisted(packageName)) return false

        val service = JarvisAccessibilityService.instance ?: return false
        val rootNode = service.rootInActiveWindow ?: return false

        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        nodes?.firstOrNull()?.let { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }

    /**
     * 在目标 App 中输入文本
     */
    fun inputText(packageName: String, text: String): Boolean {
        if (!isWhitelisted(packageName)) return false

        val service = JarvisAccessibilityService.instance ?: return false
        val rootNode = service.rootInActiveWindow ?: return false

        val editTexts = findByClass(rootNode, "android.widget.EditText")
        editTexts.firstOrNull()?.let { node ->
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            return true
        }
        return false
    }

    /** 递归查找指定类名的节点（AccessibilityNodeInfo 无按类名查找的公开 API）。 */
    private fun findByClass(node: AccessibilityNodeInfo, className: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (node.className?.toString() == className) result.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            result.addAll(findByClass(child, className))
        }
        return result
    }

    /**
     * 在目标 App 中执行滑动
     */
    fun swipe(packageName: String, direction: SwipeDirection): Boolean {
        if (!isWhitelisted(packageName)) return false

        val service = JarvisAccessibilityService.instance ?: return false
        val rootNode = service.rootInActiveWindow ?: return false

        val action = when (direction) {
            SwipeDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            SwipeDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return rootNode.performAction(action)
    }

    enum class SwipeDirection { UP, DOWN }
}
