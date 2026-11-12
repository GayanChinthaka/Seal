package com.junkfood.seal.download

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction.Download
import com.junkfood.seal.download.Task.RestartableAction.FetchInfo
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.parseSpeedFromText
import com.junkfood.seal.util.sanitizeBinaryUnits
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent

private const val TAG = "DownloaderV2"

private const val MAX_CONCURRENCY = 3

/** Minimum time between two progress updates of one task */
private const val PROGRESS_UPDATE_INTERVAL_MILLIS = 250L

/** Minimum time between two backups of the task list */
private const val BACKUP_INTERVAL_MILLIS = 1000L

private const val KILL_RETRY_COUNT = 50
private const val KILL_RETRY_INTERVAL_MILLIS = 100L

interface DownloaderV2 {
    fun getTaskStateMap(): SnapshotStateMap<Task, Task.State>

    fun cancel(task: Task): Boolean

    fun cancel(taskId: String): Boolean {
        return getTaskStateMap().keys.find { it.id == taskId }?.let { cancel(it) } ?: false
    }

    fun restart(task: Task)

    /** Enqueue a [Task] with an empty [Task.State] */
    fun enqueue(task: Task)

    fun enqueue(task: Task, state: Task.State)

    fun enqueue(taskWithState: TaskFactory.TaskWithState) {
        val (task, state) = taskWithState
        enqueue(task, state)
    }

    fun remove(task: Task): Boolean
}

internal object FakeDownloaderV2 : DownloaderV2 {
    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return mutableStateMapOf()
    }

    override fun cancel(task: Task): Boolean {
        return false
    }

    override fun restart(task: Task) {}

    override fun enqueue(task: Task) {}

    override fun enqueue(task: Task, state: Task.State) {}

    override fun remove(task: Task): Boolean {
        return true
    }
}

/**
 * TODO:
 *     - Notification
 *     - Custom commands
 *     - States for ViewModels
 */
@OptIn(FlowPreview::class)
class DownloaderV2Impl(private val appContext: Context) : DownloaderV2, KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskStateMap = mutableStateMapOf<Task, Task.State>()
    private val snapshotFlow = snapshotFlow { taskStateMap.toMap() }

    /**
     * Guards every change of [taskStateMap] that depends on the current state. The states are
     * changed by the UI, by the scheduler, and by the threads that read the output of yt-dlp.
     */
    private val lock = Any()

    private val runCounter = AtomicInteger()

    /** The most recent run of each task by [Task.id], whether it has ended or not */
    private val latestRuns = ConcurrentHashMap<String, Job>()

    init {
        scope.launch(Dispatchers.Default) {
            snapshotFlow
                .onEach {
                    // A failure here must not end the collection, or no task would start again
                    runCatching { doYourWork() }.onFailure { Log.e(TAG, "doYourWork", it) }
                }
                .map { it.countRunning() }
                .distinctUntilChanged()
                .collect { if (it > 0) App.startService() else App.stopService() }
        }

        scope.launch(Dispatchers.IO) {
            // don't write before we read
            enqueueFromBackup()

            snapshotFlow
                .map { it.filter { it.value.downloadState !is Completed } }
                .distinctUntilChanged()
                // The states change several times a second while downloading
                .sample(BACKUP_INTERVAL_MILLIS)
                .collect { PreferenceUtil.encodeTaskListBackup(it) }
        }
    }

    private fun enqueueFromBackup() {
        val taskList =
            PreferenceUtil.decodeTaskListBackup()
                .filter { it.value.downloadState !is Completed }
                .mapValues { (_, state) ->
                    val preState = state.downloadState
                    val downloadState =
                        when (preState) {
                            is FetchingInfo,
                            Idle -> {
                                Canceled(action = FetchInfo)
                            }
                            is Running -> {
                                Canceled(
                                    action = Download,
                                    progress = preState.progress.takeIf { it >= 0 },
                                )
                            }

                            ReadyWithInfo -> {
                                Canceled(action = Download, progress = null)
                            }
                            else -> {
                                preState
                            }
                        }
                    state.copy(downloadState = downloadState)
                }
        taskList.forEach(::enqueue)
    }

    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return taskStateMap
    }

    override fun enqueue(task: Task) {
        enqueue(task, Task.State(Idle, null, Task.ViewState(url = task.url, title = task.url)))
    }

    override fun enqueue(task: Task, state: Task.State) {
        synchronized(lock) {
            // The same task is at work already. Replacing its state would start it a second time,
            // with two processes writing to the same files.
            if (taskStateMap[task]?.downloadState is DownloadState.Cancelable) return
            taskStateMap[task] = state
        }
    }

    /**
     * Noted the caller is responsible for stopping the [task] before removing it
     *
     * @return true if the task was removed
     */
    override fun remove(task: Task): Boolean =
        synchronized(lock) {
            if (taskStateMap.contains(task)) {
                taskStateMap.remove(task)
                true
            } else {
                false
            }
        }

    override fun cancel(task: Task): Boolean = task.cancelImpl()

    override fun restart(task: Task) {
        task.restartImpl()
    }

    /** Changes the state of this task, unless the task was removed in the meantime */
    private inline fun Task.update(transform: (Task.State) -> Task.State): Boolean =
        synchronized(lock) {
            val state = taskStateMap[this] ?: return false
            taskStateMap[this] = transform(state)
            true
        }

    /**
     * Changes the state of this task on behalf of the run [job]. Does nothing when the task has
     * moved on, e.g. because the user canceled that run or has restarted the task since.
     *
     * @return true if the state was changed
     */
    private inline fun Task.updateRun(job: Job, transform: (Task.State) -> Task.State): Boolean =
        synchronized(lock) {
            val state = taskStateMap[this] ?: return false
            if ((state.downloadState as? DownloadState.Cancelable)?.job !== job) return false
            taskStateMap[this] = transform(state)
            true
        }

    private fun Task.isCurrentRun(job: Job): Boolean =
        synchronized(lock) {
            (taskStateMap[this]?.downloadState as? DownloadState.Cancelable)?.job === job
        }

    private val Task.notificationId: Int
        get() = id.hashCode()

    /** Processes pending tasks, prioritizing downloads. */
    private fun doYourWork() {
        synchronized(lock) {
            if (taskStateMap.countRunning() >= MAX_CONCURRENCY) return

            taskStateMap.entries
                .sortedBy { (_, state) -> state.downloadState }
                .firstOrNull { (_, state) ->
                    state.downloadState == ReadyWithInfo || state.downloadState == Idle
                }
                ?.let { (task, state) ->
                    when (state.downloadState) {
                        Idle -> task.prepare()
                        ReadyWithInfo -> task.download(state)
                        else -> {}
                    }
                }
        }
    }

    /**
     * Starts a run of this task: a coroutine that executes one yt-dlp process (or more, one after
     * another) and reports back through [updateRun].
     *
     * Every run has its own process id. When the user pauses and resumes a task, the process of
     * the paused run can still be shutting down. With a shared id the library took the dying
     * process for a failed one and the new process became impossible to cancel. The new run also
     * waits for the previous one to end, so that two processes never write to the same files.
     */
    private fun Task.startRun(
        toState: (job: Job, processId: String) -> DownloadState,
        block: (job: Job, processId: String) -> Unit,
    ) {
        val processId = "$id#${runCounter.incrementAndGet()}"
        val previousRun = latestRuns[id]
        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                previousRun?.join()
                if (isActive) block(coroutineContext.job, processId)
            }
        latestRuns[id] = job
        if (update { it.copy(downloadState = toState(job, processId)) }) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun Task.prepare() {
        if (type is TypeInfo.CustomCommand) {
            execute()
        } else {
            fetchInfo()
        }
    }

    private fun Task.fetchInfo() {
        val task = this
        val taskInfo = task.type
        val playlistIndex = if (taskInfo is TypeInfo.Playlist) taskInfo.index else null
        startRun(toState = { job, processId -> FetchingInfo(job = job, taskId = processId) }) {
            job,
            processId ->
            DownloadUtil.fetchVideoInfoFromUrl(
                    url = url,
                    playlistIndex = playlistIndex,
                    preferences = preferences,
                    taskKey = processId,
                )
                .onSuccess { info ->
                    // In one step, so that the download never starts with half of the info
                    task.updateRun(job) {
                        it.copy(
                            downloadState = ReadyWithInfo,
                            videoInfo = info,
                            viewState = Task.ViewState.fromVideoInfo(info),
                        )
                    }
                }
                .onFailure { throwable ->
                    if (throwable is YoutubeDL.CanceledException) {
                        return@onFailure
                    }
                    var title = ""
                    val failed =
                        task.updateRun(job) {
                            title = it.viewState.title
                            it.copy(downloadState = Error(throwable = throwable, action = FetchInfo))
                        }
                    if (failed) {
                        NotificationUtil.notifyError(
                            title = title,
                            textId = R.string.fetch_info_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
                }
        }
    }

    /** The files that the download of this task is expected to consist of */
    private fun Task.expectedParts(state: Task.State): List<DownloadProgressTracker.ExpectedPart> {
        val duration = state.videoInfo?.duration ?: 0.0
        // The sizes in the info are those of the whole video
        val sizesApply = preferences.videoClips.isEmpty()
        val formats =
            state.viewState.videoFormats.orEmpty() + state.viewState.audioOnlyFormats.orEmpty()
        return formats.map { format ->
            DownloadProgressTracker.ExpectedPart(
                formatId = format.formatId,
                size =
                    if (sizesApply) {
                        format.fileSize
                            ?: format.fileSizeApprox
                            ?: (duration * (format.tbr ?: 0.0) * 125)
                    } else {
                        0.0
                    },
                kind =
                    if (format.containsVideo()) DownloadProgressTracker.PartKind.Video
                    else DownloadProgressTracker.PartKind.Audio,
            )
        }
    }

    private class ProgressThrottle {
        var lastUpdateMillis = 0L
        var lastSnapshot: DownloadProgressTracker.Snapshot? = null

        /** @return true if the UI should be updated with [snapshot] */
        fun shouldUpdate(snapshot: DownloadProgressTracker.Snapshot): Boolean {
            val previous = lastSnapshot
            val now = System.currentTimeMillis()
            val stepChanged =
                previous == null ||
                    previous.phase != snapshot.phase ||
                    previous.partIndex != snapshot.partIndex
            if (!stepChanged && now - lastUpdateMillis < PROGRESS_UPDATE_INTERVAL_MILLIS) {
                return false
            }
            lastUpdateMillis = now
            lastSnapshot = snapshot
            return true
        }
    }

    private fun Task.download(state: Task.State) {
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        val task = this
        val info = state.videoInfo
        if (info == null) {
            // Nothing to download with, fetch the info again
            update { it.copy(downloadState = Idle) }
            return
        }
        val tracker = DownloadProgressTracker(expectedParts(state))
        val throttle = ProgressThrottle()

        startRun(toState = { job, processId -> Running(job = job, taskId = processId) }) {
            job,
            processId ->
            DownloadUtil.downloadVideo(
                    videoInfo = info,
                    taskId = processId,
                    downloadPreferences = preferences,
                    shouldRetry = { task.isCurrentRun(job) },
                    progressCallback = { _, _, line ->
                        val snapshot = tracker.onLine(line)
                        if (throttle.shouldUpdate(snapshot)) {
                            val text =
                                sanitizeBinaryUnits(
                                    DownloadProgressTracker.stripTemplateFields(line)
                                )
                            var title = ""
                            val updated =
                                task.updateRun(job) {
                                    val running =
                                        it.downloadState as? Running ?: return@updateRun it
                                    title = it.viewState.title
                                    it.copy(
                                        downloadState =
                                            running.copy(
                                                progress = snapshot.progress,
                                                progressText = text,
                                                speedText = snapshot.speedText,
                                                downloadedBytes = snapshot.downloadedBytes,
                                                totalBytes = snapshot.totalBytes,
                                                partIndex = snapshot.partIndex,
                                                partCount = snapshot.partCount,
                                                partKind = snapshot.partKind,
                                                phase = snapshot.phase,
                                            ),
                                        viewState =
                                            if (snapshot.totalBytes > 0) {
                                                it.viewState.copy(
                                                    fileSizeApprox = snapshot.totalBytes
                                                )
                                            } else {
                                                it.viewState
                                            },
                                    )
                                }
                            if (updated) {
                                val downloading =
                                    snapshot.phase == DownloadProgressTracker.Phase.Downloading
                                NotificationUtil.notifyProgress(
                                    notificationId = notificationId,
                                    // Zero shows as indeterminate, which suits the merge
                                    progress =
                                        if (downloading) (snapshot.progress * 100).toInt() else 0,
                                    text = text,
                                    title = title,
                                    taskId = id,
                                )
                            }
                        }
                    },
                )
                .onSuccess { pathList ->
                    val filePath = pathList.firstOrNull()
                    val fileSize =
                        filePath?.let { File(it) }?.takeIf { it.exists() }?.length() ?: 0L
                    var title = ""
                    val completed =
                        task.updateRun(job) {
                            title = it.viewState.title
                            it.copy(
                                downloadState = Completed(filePath),
                                viewState =
                                    if (fileSize > 0) {
                                        it.viewState.copy(fileSizeApprox = fileSize.toDouble())
                                    } else {
                                        it.viewState
                                    },
                            )
                        }
                    if (!completed) return@onSuccess

                    val text =
                        appContext.getString(
                            if (pathList.isEmpty()) R.string.status_completed
                            else R.string.download_finish_notification
                        )
                    FileUtil.createIntentForOpeningFile(filePath).run {
                        NotificationUtil.finishNotification(
                            notificationId,
                            title = title,
                            text = text,
                            intent =
                                if (this != null)
                                    PendingIntent.getActivity(
                                        appContext,
                                        0,
                                        this,
                                        PendingIntent.FLAG_IMMUTABLE,
                                    )
                                else null,
                        )
                    }
                }
                .onFailure { throwable ->
                    if (throwable is YoutubeDL.CanceledException) {
                        return@onFailure
                    }
                    var title = ""
                    val failed =
                        task.updateRun(job) {
                            title = it.viewState.title
                            it.copy(downloadState = Error(throwable = throwable, action = Download))
                        }
                    if (failed) {
                        NotificationUtil.notifyError(
                            title = title,
                            textId = R.string.download_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
                }
        }
    }

    private fun Task.cancelImpl(): Boolean =
        synchronized(lock) {
            when (val preState = taskStateMap[this]?.downloadState) {
                is DownloadState.Cancelable -> {
                    val job = preState.job
                    val processId = preState.taskId
                    job.cancel()
                    if (!YoutubeDL.destroyProcessById(processId)) {
                        // The process is not there yet, e.g. right after the task has started.
                        // Stop it as soon as it shows up.
                        scope.launch {
                            repeat(KILL_RETRY_COUNT) {
                                if (job.isCompleted || YoutubeDL.destroyProcessById(processId)) {
                                    return@launch
                                }
                                delay(KILL_RETRY_INTERVAL_MILLIS)
                            }
                        }
                    }
                    val progress = (preState as? Running)?.progress?.takeIf { it >= 0 }
                    NotificationUtil.cancelNotification(notificationId)
                    update {
                        it.copy(
                            downloadState =
                                Canceled(action = preState.action, progress = progress)
                        )
                    }
                }
                Idle -> update { it.copy(downloadState = Canceled(action = FetchInfo)) }
                ReadyWithInfo -> update { it.copy(downloadState = Canceled(action = Download)) }
                else -> false
            }
        }

    private fun Task.restartImpl() {
        update {
            when (val preState = it.downloadState) {
                is DownloadState.Restartable -> {
                    it.copy(
                        downloadState =
                            when (preState.action) {
                                Download -> ReadyWithInfo
                                FetchInfo -> Idle
                            }
                    )
                }
                else -> it
            }
        }
    }

    /**
     * Execute a custom command task
     *
     * @see Task.TypeInfo.CustomCommand
     */
    private fun Task.execute() {
        val task = this
        val template = (type as? TypeInfo.CustomCommand)?.template ?: return
        val tracker = DownloadProgressTracker()
        val throttle = ProgressThrottle()

        startRun(toState = { job, processId -> Running(job = job, taskId = processId) }) {
            job,
            processId ->
            DownloadUtil.executeCustomCommandTask(url, processId, template, preferences) {
                    progressPercentage,
                    _,
                    line ->
                    // A command can download any number of videos, so the progress is that of
                    // the current file, as yt-dlp prints it
                    val snapshot = tracker.onLine(line)
                    if (throttle.shouldUpdate(snapshot)) {
                        val text = sanitizeBinaryUnits(line)
                        val progress = progressPercentage / 100f
                        val updated =
                            task.updateRun(job) {
                                val running = it.downloadState as? Running ?: return@updateRun it
                                it.copy(
                                    downloadState =
                                        running.copy(
                                            progress = progress,
                                            progressText = text,
                                            speedText =
                                                parseSpeedFromText(line) ?: running.speedText,
                                            phase = snapshot.phase,
                                        )
                                )
                            }
                        if (updated) {
                            NotificationUtil.makeNotificationForCustomCommand(
                                notificationId = notificationId,
                                taskId = id,
                                progress = (progress * 100).toInt(),
                                templateName = template.name,
                                taskUrl = url,
                                text = text,
                            )
                        }
                    }
                }
                .onFailure { throwable ->
                    if (throwable is YoutubeDL.CanceledException) {
                        return@onFailure
                    }
                    var title = ""
                    val failed =
                        task.updateRun(job) {
                            title = it.viewState.title
                            it.copy(downloadState = Error(throwable = throwable, action = Download))
                        }
                    if (failed) {
                        NotificationUtil.notifyError(
                            title = title,
                            textId = R.string.download_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
                }
                .onSuccess {
                    var title = ""
                    val completed =
                        task.updateRun(job) {
                            title = it.viewState.title
                            it.copy(downloadState = Completed(null))
                        }
                    if (completed) {
                        NotificationUtil.finishNotification(
                            notificationId = notificationId,
                            title = title,
                            text = appContext.getString(R.string.status_completed),
                            intent = null,
                        )
                    }
                }
        }
    }
}
