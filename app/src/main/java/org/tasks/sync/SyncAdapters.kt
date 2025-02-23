package org.tasks.sync

import com.todoroo.astrid.data.SyncFlags
import com.todoroo.astrid.data.SyncFlags.FORCE_CALDAV_SYNC
import com.todoroo.astrid.data.Task
import kotlinx.coroutines.*
import org.tasks.LocalBroadcastManager
import org.tasks.R
import org.tasks.data.CaldavAccount.Companion.TYPE_CALDAV
import org.tasks.data.CaldavAccount.Companion.TYPE_ETEBASE
import org.tasks.data.CaldavAccount.Companion.TYPE_OPENTASKS
import org.tasks.data.CaldavAccount.Companion.TYPE_TASKS
import org.tasks.data.CaldavDao
import org.tasks.data.GoogleTaskDao
import org.tasks.data.GoogleTaskListDao
import org.tasks.data.OpenTaskDao
import org.tasks.jobs.WorkManager
import org.tasks.jobs.WorkManager.Companion.TAG_SYNC
import org.tasks.preferences.Preferences
import java.util.concurrent.Executors.newSingleThreadExecutor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncAdapters @Inject constructor(
        workManager: WorkManager,
        private val caldavDao: CaldavDao,
        private val googleTaskDao: GoogleTaskDao,
        private val googleTaskListDao: GoogleTaskListDao,
        private val openTaskDao: OpenTaskDao,
        private val preferences: Preferences,
        private val localBroadcastManager: LocalBroadcastManager
) {
    private val scope = CoroutineScope(newSingleThreadExecutor().asCoroutineDispatcher() + SupervisorJob())
    private val sync = Debouncer(TAG_SYNC) { workManager.sync(it) }
    private val syncStatus = Debouncer("sync_status") { newState ->
        val currentState = preferences.getBoolean(R.string.p_sync_ongoing_android, false)
        if (currentState != newState && isOpenTaskSyncEnabled()) {
            preferences.setBoolean(R.string.p_sync_ongoing_android, newState)
            localBroadcastManager.broadcastRefresh()
        }
    }

    fun sync(task: Task, original: Task?) = scope.launch {
        if (task.checkTransitory(SyncFlags.SUPPRESS_SYNC)) {
            return@launch
        }
        val needsGoogleTaskSync = !task.googleTaskUpToDate(original)
                && googleTaskDao.getAllByTaskId(task.id).isNotEmpty()
        val needsIcalendarSync = (task.checkTransitory(FORCE_CALDAV_SYNC) || !task.caldavUpToDate(original))
            && caldavDao.isAccountType(task.id, TYPE_ICALENDAR)
        if (needsGoogleTaskSync || needsIcalendarSync) {
            sync.sync(false)
        }
    }

    fun setOpenTaskSyncActive(active: Boolean) = scope.launch {
        syncStatus.sync(active)
    }

    fun syncOpenTasks() = scope.launch {
        sync.sync(true)
    }

    fun sync() {
        sync(false)
    }

    fun sync(immediate: Boolean) = scope.launch {
        val googleTasksEnabled = async { isGoogleTaskSyncEnabled() }
        val caldavEnabled = async { isSyncEnabled() }
        val opentasksEnabled = async { isOpenTaskSyncEnabled() }

        if (googleTasksEnabled.await() || caldavEnabled.await() || opentasksEnabled.await()) {
            sync.sync(immediate)
        }
    }

    private suspend fun isGoogleTaskSyncEnabled() = googleTaskListDao.getAccounts().isNotEmpty()

    private suspend fun isSyncEnabled() =
            caldavDao.getAccounts(TYPE_CALDAV, TYPE_TASKS, TYPE_ETEBASE).isNotEmpty()

    private suspend fun isOpenTaskSyncEnabled() = openTaskDao.shouldSync()

    companion object {
        private val TYPE_ICALENDAR = listOf(
            TYPE_CALDAV,
            TYPE_TASKS,
            TYPE_ETEBASE,
            TYPE_OPENTASKS
        )
    }
}