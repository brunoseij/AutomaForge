package com.automaforge.android.di

import android.content.Context
import com.automaforge.android.data.ai.*
import com.automaforge.android.data.game.GameStore
import com.automaforge.android.data.local.AutomaDatabase
import com.automaforge.android.data.repository.*
import com.automaforge.android.data.security.ApiKeyStore
import com.automaforge.android.domain.repository.*
import com.automaforge.android.presentation.AutomaViewModelFactory
import dagger.*
import javax.inject.Singleton

@Module
object AppModule {
    @Provides @Singleton fun database(context: Context) = AutomaDatabase.open(context)

    @Provides @Singleton fun settings(context: Context) = AiSettings(context)

    @Provides @Singleton fun keys(context: Context) = ApiKeyStore(context)

    @Provides @Singleton fun ai(context: Context) = AiClient(context)

    @Provides fun games(repository: RoomGameLibraryRepository): GameLibraryRepository = repository

    @Provides fun host(repository: LanHostRepository): HostRepository = repository

    @Provides
    fun settingsRepository(repository: LocalAiConfigurationRepository): AiConfigurationRepository =
        repository

    @Provides fun captures(repository: LanCaptureRepository): CaptureRepository = repository
}

@Singleton
@Component(modules = [AppModule::class])
interface AppComponent {
    fun database(): AutomaDatabase

    fun store(): GameStore

    fun viewModels(): AutomaViewModelFactory

    fun cameras(): com.automaforge.android.data.camera.CameraControllerFactory

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance context: Context): AppComponent
    }
}
