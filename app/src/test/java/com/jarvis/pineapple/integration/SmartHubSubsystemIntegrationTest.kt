package com.jarvis.pineapple.integration

import com.jarvis.pineapple.autonomous.ProactiveNotifier
import com.jarvis.pineapple.autonomous.TaskScheduler
import com.jarvis.pineapple.health.FatigueMonitor
import com.jarvis.pineapple.health.HealthManager
import com.jarvis.pineapple.health.VitalSignsMonitor
import com.jarvis.pineapple.perception.BehaviorPredictor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能中枢子系统集成测试：验证本次接入 UI 的纯 Kotlin 子系统端到端可用，
 * 覆盖行为预测、任务调度、主动预警、健康趋势与疲劳检测。
 */
class SmartHubSubsystemIntegrationTest {

    @Test
    fun behaviorPredictor_recordsAndPredicts() {
        val predictor = BehaviorPredictor()
        repeat(5) {
            predictor.recordBehavior(
                BehaviorPredictor.BehaviorRecord(
                    action = "喝水",
                    category = BehaviorPredictor.BehaviorCategory.MEAL
                )
            )
        }
        val stats = predictor.getBehaviorStatistics()
        assertEquals(5, stats.totalRecords)
        assertTrue(stats.categoryDistribution.containsKey("MEAL"))

        val prediction = predictor.predictNextBehavior()
        assertNotNull(prediction)
        assertTrue(prediction.confidence in 0f..1f)
    }

    @Test
    fun taskScheduler_runsHighestPriorityFirst() = runBlocking {
        val scheduler = TaskScheduler()
        val order = mutableListOf<String>()
        scheduler.schedule(TaskScheduler.Task("a", "低", 1) { order.add("a") })
        scheduler.schedule(TaskScheduler.Task("b", "高", 10) { order.add("b") })

        assertTrue(scheduler.hasPending())
        scheduler.runNext()
        scheduler.runNext()
        assertFalse(scheduler.hasPending())
        assertEquals(listOf("b", "a"), order)
    }

    @Test
    fun proactiveNotifier_emitsAlert() = runBlocking {
        val notifier = ProactiveNotifier()
        val received = withTimeoutOrNull(2_000) {
            launch {
                notifier.notify("test", "测试预警", ProactiveNotifier.Severity.WARNING)
            }
            notifier.alerts.first()
        }
        assertNotNull(received)
        assertEquals("test", received?.type)
        assertEquals(ProactiveNotifier.Severity.WARNING, received?.severity)
    }

    @Test
    fun healthManager_trendClassification() {
        val health = HealthManager()
        assertEquals("无数据", health.analyzeTrend(emptyList()))

        val fast = List(3) { VitalSignsMonitor.VitalSigns(heartRate = 120, source = "sensor") }
        assertEquals("心率偏快", health.analyzeTrend(fast))

        val slow = List(3) { VitalSignsMonitor.VitalSigns(heartRate = 45, source = "sensor") }
        assertEquals("心率偏慢", health.analyzeTrend(slow))

        val normal = List(3) { VitalSignsMonitor.VitalSigns(heartRate = 72, source = "sensor") }
        assertEquals("心率正常范围", health.analyzeTrend(normal))
    }

    @Test
    fun fatigueMonitor_detectsFatigueFromClosedEyes() {
        val fatigue = FatigueMonitor()
        assertFalse(fatigue.detectFatigue(0.35f, 0))
        // 连续闭眼（EAR 低）应识别为疲劳
        var tired = false
        repeat(15) { tired = fatigue.detectFatigue(0.1f, 0) }
        assertTrue(tired)
        assertTrue(fatigue.perclos() > 0.3f)
    }
}
