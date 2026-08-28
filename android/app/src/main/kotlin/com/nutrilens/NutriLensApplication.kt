package com.nutrilens

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.nutrilens.core.data.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The application entry point.
 *
 * Implements [Configuration.Provider] so WorkManager is created with Hilt's
 * worker factory; the manifest removes WorkManager's own initializer to make
 * that the only path. A worker cannot otherwise receive injected dependencies,
 * and the sync worker needs the whole data layer.
 */
@HiltAndroidApp
class NutriLensApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Schedule the recurring sync once, at startup. The request is unique
        // and KEEP, so repeated launches do not reset its interval.
        SyncWorker.enqueuePeriodic(WorkManager.getInstance(this))
    }
}
