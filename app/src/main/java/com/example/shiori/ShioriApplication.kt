package com.example.shiori

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.shiori.core.util.NotificationConstants
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ShioriApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // 解析中（フォアグラウンド用）
            nm.createNotificationChannel(
                NotificationChannel(
                    NotificationConstants.CHANNEL_PROCESSING,
                    "AI 解析",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "URLのAI解析中に表示されるバックグラウンド通知" }
            )

            // 保存完了
            nm.createNotificationChannel(
                NotificationChannel(
                    NotificationConstants.CHANNEL_RESULT,
                    "保存完了",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "ブックマーク保存完了通知" }
            )
        }
    }
}
