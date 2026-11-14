package ltd.usage.tracker

import android.app.Application
import ltd.usage.tracker.data.Store
import ltd.usage.tracker.work.RefreshScheduler

class UsageApp : Application() {

    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        // 每次冷启动都确保周期任务在位；KEEP 策略保证不会重置已有计时器
        RefreshScheduler.ensureScheduled(this)
    }
}
