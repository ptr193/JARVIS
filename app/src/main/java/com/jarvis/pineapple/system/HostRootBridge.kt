package com.jarvis.pineapple.system

import com.jarvis.pineapple.chat.RootAccessStatus
import com.jarvis.pineapple.chat.RootCommandBridge
import com.jarvis.pineapple.chat.RootRunResult

/** [RootCommandBridge] 的真实实现：转发到 [HostRootManager] 的 `su` 后端。 */
class HostRootBridge(
    private val manager: HostRootManager
) : RootCommandBridge {

    override suspend fun status(): RootAccessStatus = manager.check()

    override suspend fun run(command: String): RootRunResult = manager.execute(command)
}
