package app.flowsync

import android.app.Application
import app.flowsync.data.LogStore
import app.flowsync.data.ProfileRepository
import app.flowsync.work.Notifications
import app.flowsync.work.Scheduler

class FlowSyncApp : Application() {
    lateinit var repo: ProfileRepository
        private set

    override fun onCreate() {
        super.onCreate()
        LogStore.init(filesDir)
        repo = ProfileRepository(filesDir)
        Notifications.createChannels(this)
        Scheduler.applyAll(this, repo.profiles.value)
    }
}
