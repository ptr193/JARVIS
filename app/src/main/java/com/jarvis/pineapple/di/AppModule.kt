package com.jarvis.pineapple.di

import com.jarvis.pineapple.autonomous.AutonomousDecisionManager
import com.jarvis.pineapple.autonomous.DataManager
import com.jarvis.pineapple.autonomous.EmergencyProtocol
import com.jarvis.pineapple.autonomous.MemorySystem
import com.jarvis.pineapple.autonomous.ProactiveNotifier
import com.jarvis.pineapple.autonomous.TaskScheduler
import com.jarvis.pineapple.chat.ChatEngine
import com.jarvis.pineapple.chat.ConversationEnhancer
import com.jarvis.pineapple.chat.ConversationRepository
import com.jarvis.pineapple.chat.TranslationEngine
import com.jarvis.pineapple.cloud.CloudSyncManager
import com.jarvis.pineapple.db.DatabaseFactory
import com.jarvis.pineapple.health.HealthMonitor
import com.jarvis.pineapple.health.TelemedicineManager
import com.jarvis.pineapple.info.DatabaseQueryEngine
import com.jarvis.pineapple.info.InfoProcessor
import com.jarvis.pineapple.info.WebSearchEngine
import com.jarvis.pineapple.model.BuiltInModels
import com.jarvis.pineapple.model.ModelCapabilityScheduler
import com.jarvis.pineapple.model.ModelDownloadManager
import com.jarvis.pineapple.model.ModelImportManager
import com.jarvis.pineapple.model.ModelRegistry
import com.jarvis.pineapple.model.ModelScheduler
import com.jarvis.pineapple.model.UserLocalModel
import com.jarvis.pineapple.plugin.PluginManager
import com.jarvis.pineapple.perception.BehaviorPredictor
import com.jarvis.pineapple.perception.EnvironmentPerception
import com.jarvis.pineapple.perception.MLKitFaceDetector
import com.jarvis.pineapple.perception.MLKitObjectDetector
import com.jarvis.pineapple.perception.ObjectDetector
import com.jarvis.pineapple.perception.PerceptionManager
import com.jarvis.pineapple.personality.PersonalityRepository
import com.jarvis.pineapple.security.CryptoManager
import com.jarvis.pineapple.security.FaceRecognitionManager
import com.jarvis.pineapple.security.IntrusionDetector
import com.jarvis.pineapple.security.SecureStorage
import com.jarvis.pineapple.security.SecurityManager
import com.jarvis.pineapple.smarthome.SmartHomeAdapter
import com.jarvis.pineapple.smarthome.SmartHomeManager
import com.jarvis.pineapple.speech.SpeechManager
import com.jarvis.pineapple.speech.SpeechRecognizerManager
import com.jarvis.pineapple.speech.Phonemizer
import com.jarvis.pineapple.speech.VoiceManager
import com.jarvis.pineapple.speech.VoiceWakeManager
import com.jarvis.pineapple.system.CalendarController
import com.jarvis.pineapple.system.CommunicationManager
import com.jarvis.pineapple.system.PermissionManager
import com.jarvis.pineapple.system.MediaController
import com.jarvis.pineapple.system.CrossAppOperator
import com.jarvis.pineapple.system.DeviceControlManager
import com.jarvis.pineapple.system.DeviceMonitor
import com.jarvis.pineapple.system.HardwareDiagnostics
import com.jarvis.pineapple.system.NavigationController
import com.jarvis.pineapple.system.PhoneController
import com.jarvis.pineapple.vm.VmImageImporter
import com.jarvis.pineapple.vm.VmDistroManager
import com.jarvis.pineapple.system.RemoteAssistanceManager
import com.jarvis.pineapple.system.ScreenshotManager
import com.jarvis.pineapple.system.SmsController
import com.jarvis.pineapple.system.SystemSettingsController
import com.jarvis.pineapple.ui.chat.ChatViewModel
import com.jarvis.pineapple.vm.HavenVmManager
import com.jarvis.pineapple.vm.ProotManager
import com.jarvis.pineapple.vm.VmManager
import com.jarvis.pineapple.vm.VmPlatformManager
import com.jarvis.pineapple.vm.VmRootManager
import com.jarvis.pineapple.vm.VmTypeSelector
import com.jarvis.pineapple.wake.AppIconWakeDetector
import com.jarvis.pineapple.wake.BluetoothWakeDetector
import com.jarvis.pineapple.wake.HomeLongPressDetector
import com.jarvis.pineapple.wake.NotificationWakeDetector
import com.jarvis.pineapple.wake.PowerButtonWakeDetector
import com.jarvis.pineapple.wake.ShakeWakeDetector
import com.jarvis.pineapple.wake.TileWakeDetector
import com.jarvis.pineapple.wake.VoiceWakeDetector
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
    single { CryptoManager(get()) }

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
    single { UserLocalModel(get()) }
    single { ModelScheduler(userModel = get(), liteRtModels = BuiltInModels.liteRtModels(get())).also { ModelRegistry.scheduler = it } }
    single { ModelCapabilityScheduler(get()).also { it.registerDefaultProfiles() } }
    single { ModelDownloadManager(get()) }
    single { ModelImportManager(get()) }

    // Chat
    single { ConversationRepository(get()) }
    single { MemorySystem(get()) }
    single { ConversationEnhancer(get()) }
    single { TranslationEngine() }
    single { ChatEngine(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ChatViewModel(get(), get(), get()) }

    // System control
    single { PhoneController(get()) }
    single { SmsController(get()) }
    single { CalendarController(get()) }
    single { SystemSettingsController(get(), get()) }
    single { NavigationController(get()) }
    single { DeviceMonitor(get()) }
    single { CrossAppOperator(get()) }
    single { CommunicationManager(get()) }
    single { DeviceControlManager(get()) }
    single { ScreenshotManager(get()) }
    single { HardwareDiagnostics(get()) }
    single { RemoteAssistanceManager(get()) }

    // Wake
    single { WakeEngine(get()) }
    single { VoiceWakeDetector(get()) }
    single { PowerButtonWakeDetector(get()) }
    single { NotificationWakeDetector(get()) }
    single { ShakeWakeDetector(get()) }
    single { BluetoothWakeDetector(get()) }
    single { TileWakeDetector.getInstance() }
    single { AppIconWakeDetector(get()) }
    single { HomeLongPressDetector(get()) }

    // Permission manager (singleton)
    single { PermissionManager }
    single { MediaController(get()) }

    // Virtual machine
    single { VmManager(get<android.content.Context>()) }
    single { ProotManager(get()) }
    single { VmDistroManager(get(), get()) }
    single { HavenVmManager(get(), get(), get()) }
    single { VmRootManager(get()) }
    single { VmPlatformManager(get()) }
    single { VmTypeSelector() }
    single { VmImageImporter(get()) }

    // Smart home
    single { SmartHomeAdapter() }
    single { SmartHomeManager(get()) }

    // Perception
    single { EnvironmentPerception(get()) }
    single { ObjectDetector(get()) }
    single { PerceptionManager(get()) }
    single { BehaviorPredictor() }
    single { MLKitFaceDetector(get()) }
    single { MLKitObjectDetector(get()) }

    // Autonomous
    single { TaskScheduler() }
    single { ProactiveNotifier() }
    single { EmergencyProtocol(get()) }
    single { DataManager(get()) }
    single { AutonomousDecisionManager(get()) }

    // Speech
    single { SpeechRecognizerManager(get()) }
    single { VoiceManager(get()) }
    single { SpeechManager(get()) }
    single { VoiceWakeManager(get()) }

    // Cloud
    single { CloudSyncManager() }

    // Plugins (#139)
    single { PluginManager(get()) }

    // Security
    single { SecurityManager(get()) }
    single { FaceRecognitionManager(get()) }
    single { IntrusionDetector(get()) }

    // Health
    single<com.jarvis.pineapple.health.MedicationReminderScheduler> {
        val comm = get<CommunicationManager>()
        com.jarvis.pineapple.health.MedicationReminderScheduler { id, title, description, triggerTime ->
            comm.addReminder(
                CommunicationManager.Reminder(
                    id = id,
                    title = title,
                    description = description,
                    triggerTime = triggerTime
                )
            )
        }
    }
    single { HealthMonitor(get(), get(), get()) }
    single { com.jarvis.pineapple.health.HealthManager(get()) }
    single { TelemedicineManager(get()) }

    // Info processing
    single { DatabaseQueryEngine(get()) }
    single { InfoProcessor(get(), get()) }
    single { WebSearchEngine() }
}
