package com.jarvis.pineapple.service

import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * InCallService 实现，用于处理电话呼叫
 */
@RequiresApi(Build.VERSION_CODES.M)
class JarvisInCallService : InCallService() {

    companion object {
        private const val TAG = "JarvisInCallService"
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "Call added: ${call.details.handle}")
        // 处理来电
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "Call removed: ${call.details.handle}")
    }

}