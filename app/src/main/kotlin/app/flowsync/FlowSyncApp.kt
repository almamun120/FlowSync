package app.flowsync

import android.app.Application
import app.flowsync.data.LogStore
import app.flowsync.data.ProfileRepository
import app.flowsync.data.RcloneStore
import app.flowsync.work.Notifications
import app.flowsync.work.Scheduler

class FlowSyncApp : Application() {
    lateinit var repo: ProfileRepository
        private set
    lateinit var rcloneStore: RcloneStore
        private set

    override fun onCreate() {
        super.onCreate()
        LogStore.init(filesDir)
        app.flowsync.rclone.RcloneManager.init(this)
        repo = ProfileRepository(filesDir)
        rcloneStore = RcloneStore(filesDir)
        Notifications.createChannels(this)
        Scheduler.applyAll(this, repo.profiles.value)
        rcloneStore.tasks.value.forEach { Scheduler.applyTask(this, it) }
    }
}
