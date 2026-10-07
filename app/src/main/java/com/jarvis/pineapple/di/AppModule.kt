package com.jarvis.pineapple.di

import com.jarvis.pineapple.autonomous.DataManager
import com.jarvis.pineapple.autonomous.EmergencyProtocol
import com.jarvis.pineapple.autonomous.MemorySystem
import com.jarvis.pineapple.autonomous.ProactiveNotifier
import com.jarvis.pineapple.autonomous.TaskScheduler
import com.jarvis.pineapple.chat.ChatEngine
import com.jarvis.pineapple.db.DatabaseFactory
import com.jarvis.pineapple.model.BuiltInModels
import com.jarvis.pineapple.model.ModelScheduler
import com.jarvis.pineapple.model.UserLocalModel
import com.jarvis.pineapple.perception.EnvironmentPerception
import com.jarvis.pineapple.perception.ObjectDetector
import com.jarvis.pineapple.personality.PersonalityRepository
import com.jarvis.pineapple.security.SecureStorage
import com.jarvis.pineapple.smarthome.SmartHomeAdapter
import com.jarvis.pineapple.system.CalendarController
import com.jarvis.pineapple.system.PhoneController
import com.jarvis.pineapple.system.SmsController
import com.jarvis.pineapple.system.SystemSettingsController
import com.jarvis.pineapple.ui.chat.ChatViewModel
import com.jarvis.pineapple.vm.VmManager
import com.jarvis.pineapple.wake.WakeEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { PersonalityRepository(get()) }
    single { SecureStorage(get()) }

    single { DatabaseFactory.create(get()) }

    single {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            install(Logging)
        }
    }

    // Model layer
    single { UserLocalModel() }
    single { ModelScheduler(userModel = get(), liteRtModels = BuiltInModels.liteRtModels) }

    // Chat
    single { ChatEngine(get(), get()) }
    viewModel { ChatViewModel(get(), get()) }

    // System control
    single { PhoneController(get()) }
    single { SmsController(get()) }
    single { CalendarController(get()) }
    single { SystemSettingsController(get()) }

    // Wake
    single { WakeEngine(get()) }

    // Virtual machine
    single { VmManager() }

    // Smart home
    single { SmartHomeAdapter() }

    // Perception
    single { EnvironmentPerception(get()) }
    single { ObjectDetector() }

    // Autonomous
    single { TaskScheduler() }
    single { ProactiveNotifier() }
    single { EmergencyProtocol() }
    single { MemorySystem() }
    single { DataManager() }
}
