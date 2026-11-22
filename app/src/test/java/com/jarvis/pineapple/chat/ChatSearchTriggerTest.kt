package com.jarvis.pineapple.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 联网检索意图识别（纯函数）测试。
 * 仅验证查询抽取逻辑，不访问网络。
 */
class ChatSearchTriggerTest {

    @Test
    fun extractsQueryAfterExplicitSearchVerb() {
        assertEquals("杭州天气", ChatEngine.detectSearchQuery("帮我查一下：杭州天气"))
        assertEquals("kotlin coroutines", ChatEngine.detectSearchQuery("搜索一下 kotlin coroutines"))
        assertEquals("地铁 末班车", ChatEngine.detectSearchQuery("上网查地铁 末班车"))
    }

    @Test
    fun supportsEnglishSearchVerbs() {
        assertEquals("android 15 features", ChatEngine.detectSearchQuery("search for android 15 features"))
        assertEquals("weather tokyo", ChatEngine.detectSearchQuery("look up weather tokyo"))
    }

    @Test
    fun freshnessQuestionWithoutSearchVerbStillTriggers() {
        assertEquals("今天北京天气怎么样？", ChatEngine.detectSearchQuery("今天北京天气怎么样？"))
        assertEquals("比特币现在什么价格", ChatEngine.detectSearchQuery("比特币现在什么价格"))
    }

    @Test
    fun ordinaryChatDoesNotTriggerSearch() {
        assertNull(ChatEngine.detectSearchQuery("你好，介绍一下你自己"))
        assertNull(ChatEngine.detectSearchQuery("帮我写一个冒泡排序"))
        assertNull(ChatEngine.detectSearchQuery("搜索"))
    }

    @Test
    fun blankInputReturnsNull() {
        assertNull(ChatEngine.detectSearchQuery("   "))
    }
}
