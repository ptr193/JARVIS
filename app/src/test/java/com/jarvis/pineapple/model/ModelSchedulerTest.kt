package com.jarvis.pineapple.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSchedulerTest {

    private class FakeModel(
        id: String,
        name: String,
        source: ModelSource,
        priority: Int,
        private val becomesReady: Boolean
    ) : AiModel {
        override val info = ModelInfo(
            id = id,
            name = name,
            source = source,
            description = name,
            priority = priority
        )
        override var state: ModelState = ModelState.UNLOADED
            private set

        override suspend fun load() {
            state = if (becomesReady) ModelState.READY else ModelState.ERROR
        }

        override suspend fun unload() {
            state = ModelState.UNLOADED
        }

        override suspend fun generate(prompt: String, systemPrompt: String?): String =
            if (state == ModelState.READY) "ok" else ModelScheduler.NO_MODEL_MESSAGE

        override fun stream(prompt: String, systemPrompt: String?): Flow<String> =
            flowOf(if (state == ModelState.READY) "ok" else ModelScheduler.NO_MODEL_MESSAGE)
    }

    @Test
    fun `ranked list is user then litert then api then server`() {
        val user = FakeModel("local_user", "用户自有模型", ModelSource.LOCAL_USER, 100, false)
        val lite = FakeModel("gemma", "Gemma", ModelSource.LOCAL_LITERT, 50, false)
        val scheduler = ModelScheduler(userModel = user, liteRtModels = listOf(lite))
        scheduler.addApiModel(FakeModel("api", "API", ModelSource.API, 20, true))
        scheduler.addServerModel(FakeModel("srv", "Server", ModelSource.SERVER, 10, true))

        val ids = scheduler.rankedModels().map { it.info.id }
        assertEquals(listOf("local_user", "gemma", "api", "srv"), ids)
        assertEquals(ModelSource.LOCAL_USER, scheduler.getActive().info.source)
    }

    @Test
    fun `resolve falls back from missing user model to litert`() = runBlocking {
        val user = FakeModel("local_user", "用户自有模型", ModelSource.LOCAL_USER, 100, false)
        val lite = FakeModel("gemma", "Gemma", ModelSource.LOCAL_LITERT, 50, true)
        val scheduler = ModelScheduler(userModel = user, liteRtModels = listOf(lite))

        val resolved = scheduler.resolveOrLoad()
        assertEquals("gemma", resolved?.info?.id)
        assertEquals(ModelState.READY, resolved?.state)
        assertEquals("gemma", scheduler.getActive().info.id)
    }

    @Test
    fun `resolve falls back to api when local models fail`() = runBlocking {
        val user = FakeModel("local_user", "用户自有模型", ModelSource.LOCAL_USER, 100, false)
        val lite = FakeModel("gemma", "Gemma", ModelSource.LOCAL_LITERT, 50, false)
        val api = FakeModel("api", "API", ModelSource.API, 20, true)
        val scheduler = ModelScheduler(userModel = user, liteRtModels = listOf(lite))
        scheduler.addApiModel(api)

        val resolved = scheduler.resolveOrLoad()
        assertEquals("api", resolved?.info?.id)
        assertEquals(ModelSource.API, resolved?.info?.source)
    }

    @Test
    fun `resolve returns null and keeps explicit error when nothing loads`() = runBlocking {
        val user = FakeModel("local_user", "用户自有模型", ModelSource.LOCAL_USER, 100, false)
        val lite = FakeModel("gemma", "Gemma", ModelSource.LOCAL_LITERT, 50, false)
        val scheduler = ModelScheduler(userModel = user, liteRtModels = listOf(lite))

        assertNull(scheduler.resolveOrLoad())
        assertTrue(ModelScheduler.NO_MODEL_MESSAGE.contains("无可用模型"))
        assertTrue(ModelScheduler.NO_MODEL_MESSAGE.contains("导入"))
    }
}
