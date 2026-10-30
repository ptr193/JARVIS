package com.jarvis.pineapple.personality

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 人格（灵魂）持久化仓库。
 */
class PersonalityRepository(private val context: Context) {

    private val Context.dataStore by preferencesDataStore(name = "jarvis_personality")

    private val keyName = stringPreferencesKey("name")
    private val keyUserTitle = stringPreferencesKey("user_title")
    private val keySystemPrompt = stringPreferencesKey("system_prompt")

    val personality: Flow<Personality> = context.dataStore.data.map { prefs ->
        Personality(
            name = prefs[keyName] ?: Personality.DEFAULT.name,
            userTitle = prefs[keyUserTitle] ?: Personality.DEFAULT.userTitle,
            systemPrompt = prefs[keySystemPrompt] ?: DEFAULT_SYSTEM_PROMPT
        )
    }

    /** 读取当前人格（挂起）。 */
    suspend fun current(): Personality = personality.first()

    /** 仅更新 system prompt，保留其它字段。 */
    suspend fun updateSystemPrompt(prompt: String) {
        context.dataStore.edit { prefs ->
            prefs[keySystemPrompt] = prompt
        }
    }

    suspend fun updatePersonality(personality: Personality) {
        context.dataStore.edit { prefs ->
            prefs[keyName] = personality.name
            prefs[keyUserTitle] = personality.userTitle
            prefs[keySystemPrompt] = personality.systemPrompt
        }
    }

    suspend fun resetToDefault() {
        updatePersonality(Personality.DEFAULT)
    }

    fun buildSystemPrompt(personality: Personality): String {
        return personality.systemPrompt.replace("先生/女士", personality.userTitle)
    }
}
