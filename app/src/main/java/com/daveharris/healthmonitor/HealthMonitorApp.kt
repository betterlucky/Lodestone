package com.daveharris.healthmonitor

import android.app.Application
import com.daveharris.healthmonitor.data.AppDatabase
import com.daveharris.healthmonitor.data.DailyReviewRepository
import com.daveharris.healthmonitor.data.ProbeRepository
import com.daveharris.healthmonitor.polar.PolarProbeManager

class HealthMonitorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.create(this)
        val polarManager = PolarProbeManager(this, database)
        val repository = ProbeRepository(database, polarManager)
        val dailyReviewRepository = DailyReviewRepository(database)
        container = AppContainer(database, polarManager, repository, dailyReviewRepository)
    }
}

data class AppContainer(
    val database: AppDatabase,
    val polarManager: PolarProbeManager,
    val repository: ProbeRepository,
    val dailyReviewRepository: DailyReviewRepository
)
