package ltd.usage.tracker.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ltd.usage.tracker.UsageApp
import ltd.usage.tracker.data.CredentialState
import ltd.usage.tracker.data.UsageSync

/**
 * 后台用量刷新。
 *
 * 用 [CoroutineWorker] 而非 Worker：网络请求是阻塞的 OkHttp 调用，
 * 放进 Dispatchers.IO 才不会占用 WorkManager 的默认线程。
 *
 * 返回值语义：
 *  - success：正常完成（即使个别账号报错——那些错误已写进快照，不是任务失败）
 *  - retry：整批刷新抛异常（如磁盘写失败），交给 WorkManager 按退避策略重试
 *
 * 注意：这里不做「无账号就失败」的判断，没有账号时直接成功返回，
 * 否则会白白消耗重试配额。
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? UsageApp ?: return Result.success()
        // 解密失败只记下状态（load 时已备份），不重试、不写文件
        if (app.store.loadCredentialState() is CredentialState.Undecryptable) {
            return Result.success()
        }
        return runCatching {
            UsageSync.refreshAll(app.store)
            Result.success()
        }.getOrElse {
            // 超过重试次数后 WorkManager 会放弃，这里不再额外限制
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val NAME = "usage-refresh"
        private const val MAX_ATTEMPTS = 3
    }
}
