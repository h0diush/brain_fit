package app.brainfit.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.brainfit.core.sensors.SensorShakeMonitor
import app.brainfit.core.sensors.ShakeMonitor
import app.brainfit.core.time.MonotonicClock
import app.brainfit.core.time.WallClock
import app.brainfit.data.db.BrainFitDatabase
import app.brainfit.data.db.DifficultyDao
import app.brainfit.data.db.Migrations
import app.brainfit.data.db.SessionDao
import app.brainfit.data.db.TrialDao
import app.brainfit.data.db.WorkoutDao
import app.brainfit.data.repository.DifficultyRepositoryImpl
import app.brainfit.data.repository.SessionRepositoryImpl
import app.brainfit.data.repository.SettingsRepositoryImpl
import app.brainfit.data.repository.WorkoutRepositoryImpl
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.SessionRepository
import app.brainfit.domain.repository.SettingsRepository
import app.brainfit.domain.repository.WorkoutRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BrainFitDatabase =
        Room.databaseBuilder(context, BrainFitDatabase::class.java, BrainFitDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides
    fun provideSessionDao(db: BrainFitDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideTrialDao(db: BrainFitDatabase): TrialDao = db.trialDao()

    @Provides
    fun provideDifficultyDao(db: BrainFitDatabase): DifficultyDao = db.difficultyDao()

    @Provides
    fun provideWorkoutDao(db: BrainFitDatabase): WorkoutDao = db.workoutDao()

    /** System.nanoTime — та же шкала, что у времени кадров Compose (withFrameNanos). */
    @Provides
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock { System.nanoTime() }

    @Provides
    fun provideWallClock(): WallClock = WallClock { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindSettings(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindSessions(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    abstract fun bindDifficulty(impl: DifficultyRepositoryImpl): DifficultyRepository

    @Binds
    abstract fun bindWorkouts(impl: WorkoutRepositoryImpl): WorkoutRepository

    @Binds
    abstract fun bindShakeMonitor(impl: SensorShakeMonitor): ShakeMonitor
}
