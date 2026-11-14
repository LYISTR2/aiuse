package ltd.usage.tracker.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 后台刷新的调度入口。
 *
 * 策略：
 *  - 周期 6 小时。WorkManager 的周期任务最小间隔是 15 分钟，但用量数据变化慢，
 *    刷太勤只会浪费电和 API 配额；6 小时足够让余额不至于长期失真。
 *  - 要求联网（CONNECTED）且电量不低，否则等到条件满足再跑，不在飞行模式下空转。
 *  - 用 KEEP 策略：已排队的任务不重复登记，避免每次启动都重置计时器，
 *    导致「每次打开 App 都把下一次刷新推后 6 小时」。
 *  - 首次安装/升级后立即补一次（[runNow]），否则要等满 6 小时才有数据。
 */
object RefreshScheduler {

    private const val PERIOD_HOURS = 6L

    /**
     * 确保周期任务已登记。
     *
     * 这里必须容错：WorkManager 由 androidx.startup 在 Application.onCreate 之后初始化，
     * 若宿主环境自行接管初始化（自定义 Configuration）或初始化失败，
     * [WorkManager.getInstance] 会抛 IllegalStateException。
     * 后台刷新只是增强功能，不能因为它让整个 App 启动失败——
     * 前台「刷新」按钮仍可用，所以这里降级为静默跳过。
     */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                RefreshWorker.NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    /** 立即触发一次刷新（用户点「刷新」或 App 冷启动时用）。 */
    fun runNow(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        runCatching { WorkManager.getInstance(context).enqueue(request) }
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(RefreshWorker.NAME) }
    }
}
