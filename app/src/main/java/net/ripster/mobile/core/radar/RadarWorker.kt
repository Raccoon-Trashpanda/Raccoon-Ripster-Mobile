package net.ripster.mobile.core.radar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.ripster.mobile.RipsterApp
import java.util.concurrent.TimeUnit

/**
 * Периодическая проверка локального радара (без ПК). Раз в ~6 часов дёргает
 * [LocalRadar.refresh]; если у кого-то из отслеживаемых появился новый релиз —
 * одно сводное уведомление. Сеть обязательна, батарею не насилуем (обычный,
 * не expedited воркер).
 */
class RadarWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = RipsterApp.from(applicationContext)
        val fresh = runCatching { app.localRadar.refresh() }.getOrDefault(0)
        if (fresh > 0) notify(applicationContext, fresh)
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "ripster-local-radar"
        private const val CHANNEL = "radar"

        fun schedule(context: Context) {
            ensureChannel(context)
            val req = PeriodicWorkRequestBuilder<RadarWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setInitialDelay(20, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Разовая проверка сейчас (кнопка «обновить» в Радаре). */
        suspend fun runNow(context: Context): Int {
            val app = RipsterApp.from(context)
            val n = runCatching { app.localRadar.refresh() }.getOrDefault(0)
            if (n > 0) notify(context, n)
            return n
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Новые релизы", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Радар: новинки отслеживаемых артистов"
                    },
                )
            }
        }

        private fun notify(context: Context, count: Int) {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val text = if (count == 1) "У отслеживаемого артиста вышел новый релиз"
            else "Новые релизы у отслеживаемых артистов: $count"
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Радар Ripster")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            nm.notify(0x2A0DA2, n)
        }
    }
}
