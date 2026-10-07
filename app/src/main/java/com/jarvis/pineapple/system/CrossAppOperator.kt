package com.jarvis.pineapple.system

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 跨应用操作：通过辅助功能操作其他 App。
 * 提供 UI 节点查找、点击/滑动/输入，带白名单机制。
 */
class CrossAppOperator(private val service: AccessibilityService) {

    // 白名单：允许操作的应用包名
    private val whitelist = mutableSetOf<String>()

    fun addToWhitelist(packageName: String) { whitelist.add(packageName) }
    fun removeFromWhitelist(packageName: String) { whitelist.remove(packageName) }
    fun isAllowed(packageName: String): Boolean = packageName in whitelist

    /** 查找 UI 节点 */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        return service.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
    }

    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        return service.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }

    /** 点击 */
    fun click(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /** 输入文本 */
    fun inputText(node: AccessibilityNodeInfo?, text: String): Boolean {
        node ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 滑动 */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        service.dispatchGesture(gesture, null, null)
    }
}
