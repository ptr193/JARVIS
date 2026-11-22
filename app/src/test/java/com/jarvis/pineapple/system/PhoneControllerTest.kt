package com.jarvis.pineapple.system

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneControllerTest {

    @Test
    fun answerReadinessRequiresSdkOOrAbove() {
        assertEquals(
            AnswerReadiness.UNSUPPORTED_SDK,
            PhoneController.answerReadiness(Build.VERSION_CODES.O - 1, true, true)
        )
    }

    @Test
    fun answerReadinessRequiresAnswerPermission() {
        assertEquals(
            AnswerReadiness.PERMISSION_REQUIRED,
            PhoneController.answerReadiness(Build.VERSION_CODES.O, false, true)
        )
    }

    @Test
    fun answerReadinessRequiresDefaultDialerRole() {
        assertEquals(
            AnswerReadiness.NOT_DEFAULT_DIALER,
            PhoneController.answerReadiness(Build.VERSION_CODES.O, true, false)
        )
    }

    @Test
    fun answerReadinessIsReadyWhenAllConditionsMet() {
        assertEquals(
            AnswerReadiness.READY,
            PhoneController.answerReadiness(Build.VERSION_CODES.O, true, true)
        )
        assertEquals(
            AnswerReadiness.READY,
            PhoneController.answerReadiness(34, true, true)
        )
    }
}
