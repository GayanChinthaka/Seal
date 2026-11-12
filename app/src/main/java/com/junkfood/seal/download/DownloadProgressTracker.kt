package com.junkfood.seal.download

import java.util.Locale

/**
 * Turns the raw output lines of yt-dlp into the overall progress of a task.
 *
 * A single task often downloads several files one after another (a video stream, then an audio
 * stream, then subtitles) and merges them at the end. yt-dlp reports each file from 0% to 100% on
 * its own, so the percentage and the "of 12.3MiB" size in one line describe the current file only.
 * This class adds the files up, and reports the merge and other post-processing steps as their own
 * [Phase], so that the user does not take them for a stalled download.
 *
 * The exact byte counts come from the fields that [PROGRESS_TEMPLATE] appends to each progress
 * line. Lines without those fields (custom commands, or a downloader that does not use the
 * template) are parsed from the human-readable text instead.
 */
class DownloadProgressTracker(private val expectedParts: List<ExpectedPart> = emptyList()) {

    enum class Phase {
        Downloading,
        Merging,
        Processing,
    }

    enum class PartKind {
        Video,
        Audio,
        Unknown,
    }

    /** A file that the task is known to download, from the video info fetched beforehand. */
    data class ExpectedPart(val formatId: String?, val size: Double, val kind: PartKind)

    data class Snapshot(
        /** Overall progress in `0f..1f`, or `-1f` when it is not known. */
        val progress: Float = -1f,
        val downloadedBytes: Double = 0.0,
        /** Total size of all files in bytes, or `0.0` when it is not known. */
        val totalBytes: Double = 0.0,
        /** 1-based index of the file being downloaded, or `0` when it is not one of the expected. */
        val partIndex: Int = 0,
        val partCount: Int = 0,
        val partKind: PartKind = PartKind.Unknown,
        val phase: Phase = Phase.Downloading,
        val speedText: String = "",
    )

    private class Part(var formatId: String?) {
        var downloaded = 0.0
        var total = 0.0
        var finished = false
    }

    private val parts = mutableListOf<Part>()
    private var startNewPart = true
    private var phase = Phase.Downloading
    private var speedText = ""
    private var maxProgress = -1f

    var snapshot = Snapshot(partCount = expectedParts.size)
        private set

    /** @return the updated [Snapshot] */
    fun onLine(line: String): Snapshot {
        val text = line.trim()
        when {
            text.contains(MARKER) -> onTemplateLine(text.substringAfter(MARKER))
            DESTINATION_REGEX.containsMatchIn(text) -> {
                startNewPart = true
                phase = Phase.Downloading
            }
            EXTRACTING_REGEX.containsMatchIn(text) -> {
                // yt-dlp starts over with a fresh extraction, e.g. when the cached info is stale
                if (parts.isNotEmpty()) reset()
            }
            text.startsWith("[download]") -> onPlainProgressLine(text)
            text.startsWith("[#") -> onAria2cLine(text)
            else -> onPostProcessorLine(text)
        }
        snapshot = buildSnapshot()
        return snapshot
    }

    private fun reset() {
        parts.clear()
        startNewPart = true
        phase = Phase.Downloading
        speedText = ""
        maxProgress = -1f
    }

    private fun currentPart(formatId: String?, downloaded: Double?): Part {
        val last = parts.lastOrNull()
        val formatChanged = formatId != null && last?.formatId != null && formatId != last.formatId
        val restarted = last != null && last.finished && (downloaded ?: 0.0) < last.downloaded
        if (last == null || startNewPart || formatChanged || restarted) {
            startNewPart = false
            return Part(formatId).also { parts += it }
        }
        if (last.formatId == null) last.formatId = formatId
        return last
    }

    private fun onTemplateLine(fields: String) {
        val values = fields.split(FIELD_SEPARATOR).map { it.trim() }
        if (values.size < 6) return
        val status = values[0]
        val formatId = values[1].takeUnless { it.isEmpty() || it == "NA" }
        val downloaded = values[2].toDoubleOrNull()
        val total = values[3].toDoubleOrNull() ?: values[4].toDoubleOrNull()
        val speed = values[5].toDoubleOrNull()

        val part = currentPart(formatId, downloaded)
        phase = Phase.Downloading
        if (total != null && total > 0) part.total = total
        if (downloaded != null) part.downloaded = downloaded
        if (status == "finished") {
            part.finished = true
            // A file that was already complete is reported with its size only
            if (downloaded == null && part.total > 0) part.downloaded = part.total
            if (part.total < part.downloaded) part.total = part.downloaded
        }
        if (speed != null && speed > 0) speedText = formatSpeed(speed)
    }

    private fun onPlainProgressLine(text: String) {
        val match = PLAIN_PROGRESS_REGEX.find(text) ?: return
        val fraction = (match.groupValues[1].toDoubleOrNull() ?: return) / 100.0
        val total = parseSize(match.groupValues[2], match.groupValues[3])
        val part = currentPart(formatId = null, downloaded = total?.times(fraction))
        phase = Phase.Downloading
        if (total != null && total > 0) {
            part.total = total
            part.downloaded = total * fraction
        }
        if (fraction >= 1.0) part.finished = true
        PLAIN_SPEED_REGEX.find(text)?.let { speed ->
            parseSize(speed.groupValues[1], speed.groupValues[2])?.let {
                if (it > 0) speedText = formatSpeed(it)
            }
        }
    }

    private fun onAria2cLine(text: String) {
        val match = ARIA2C_REGEX.find(text) ?: return
        val downloaded = parseSize(match.groupValues[1], match.groupValues[2]) ?: return
        val total = parseSize(match.groupValues[3], match.groupValues[4]) ?: return
        val part = currentPart(formatId = null, downloaded = downloaded)
        phase = Phase.Downloading
        part.total = total
        part.downloaded = downloaded
        ARIA2C_SPEED_REGEX.find(text)?.let { speed ->
            parseSize(speed.groupValues[1], speed.groupValues[2])?.let {
                if (it > 0) speedText = formatSpeed(it)
            }
        }
    }

    private fun onPostProcessorLine(text: String) {
        val tag = TAG_REGEX.find(text)?.groupValues?.get(1) ?: return
        // Some of these steps also run before the download, where they are not worth reporting
        if (parts.isEmpty()) return
        when {
            tag == "Merger" -> phase = Phase.Merging
            tag in POST_PROCESSOR_TAGS || tag.startsWith("Fixup") -> phase = Phase.Processing
        }
    }

    private fun buildSnapshot(): Snapshot {
        val seenFormatIds = parts.mapNotNull { it.formatId }.toSet()
        val remaining =
            if (seenFormatIds.isEmpty()) {
                // Without format ids we can only assume that the expected files come in order
                expectedParts.drop(parts.size)
            } else {
                // yt-dlp may pick other formats than expected, so never wait for more files
                // than the task is known to have
                val pending = expectedParts.size - parts.count { it.formatId != null }
                expectedParts
                    .filter { it.formatId != null && it.formatId !in seenFormatIds }
                    .takeLast(pending.coerceAtLeast(0))
            }

        val downloaded = parts.sumOf { it.downloaded }
        val partTotals = parts.map { part -> part.resolvedTotal() }
        val sizesKnown = partTotals.all { it > 0 } && remaining.all { it.size > 0 }

        val total = if (sizesKnown) partTotals.sum() + remaining.sumOf { it.size } else 0.0
        val rawProgress =
            when {
                parts.isEmpty() -> -1f
                sizesKnown && total > 0 -> (downloaded / total).toFloat()
                else -> {
                    // Sizes are missing: let every file count the same
                    val count = parts.size + remaining.size
                    val done =
                        parts.sumOf { part ->
                            val partTotal = part.resolvedTotal()
                            when {
                                part.finished -> 1.0
                                partTotal > 0 -> (part.downloaded / partTotal).coerceIn(0.0, 1.0)
                                else -> 0.0
                            }
                        }
                    if (parts.any { it.resolvedTotal() > 0 || it.finished }) {
                        (done / count).toFloat()
                    } else {
                        -1f
                    }
                }
            }
        if (rawProgress >= 0) {
            // Size estimates of fragmented downloads fluctuate, don't let the progress go backwards
            maxProgress = maxOf(maxProgress, rawProgress.coerceIn(0f, 1f))
        }

        val currentFormatId = parts.lastOrNull()?.formatId
        val expectedIndex =
            if (currentFormatId == null) {
                -1
            } else {
                val byId = expectedParts.indexOfFirst { it.formatId == currentFormatId }
                val byOrder = parts.count { it.formatId != null } - 1
                if (byId >= 0) byId else byOrder.takeIf { it in expectedParts.indices } ?: -1
            }
        val showPart = phase == Phase.Downloading && expectedIndex >= 0 && expectedParts.size > 1

        return Snapshot(
            progress = maxProgress,
            downloadedBytes = downloaded,
            totalBytes = maxOf(total, if (total > 0) downloaded else 0.0),
            partIndex = if (showPart) expectedIndex + 1 else 0,
            partCount = expectedParts.size,
            partKind = if (showPart) expectedParts[expectedIndex].kind else PartKind.Unknown,
            phase = phase,
            speedText = if (phase == Phase.Downloading) speedText else "",
        )
    }

    private fun Part.resolvedTotal(): Double =
        when {
            total > 0 -> maxOf(total, downloaded)
            finished -> downloaded
            else ->
                expectedParts
                    .firstOrNull { it.formatId != null && it.formatId == formatId }
                    ?.size
                    ?.let { if (it > 0) maxOf(it, downloaded) else 0.0 } ?: 0.0
        }

    companion object {
        private const val MARKER = "[seal]"
        private const val FIELD_SEPARATOR = "|"

        /**
         * Value for the `--progress-template` option of yt-dlp. Keeps the default progress text and
         * appends the exact numbers, which [onLine] reads and [stripTemplateFields] removes.
         */
        const val PROGRESS_TEMPLATE =
            "download:[download] %(progress._default_template)s " +
                MARKER +
                "%(progress.status)s|%(info.format_id)s|%(progress.downloaded_bytes)s" +
                "|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s"

        /** Removes the fields appended by [PROGRESS_TEMPLATE] from a line shown to the user. */
        fun stripTemplateFields(line: String): String = line.substringBefore(MARKER).trimEnd()

        private val DESTINATION_REGEX =
            Regex("""^\[download] (Destination: |.* has already been downloaded)""")
        private val EXTRACTING_REGEX = Regex("""^\[[^\]]+] Extracting URL""")
        private val TAG_REGEX = Regex("""^\[([A-Za-z0-9]+)]""")
        private val PLAIN_PROGRESS_REGEX =
            Regex("""^\[download]\s+([\d.]+)%(?:\s+of\s+~?\s*([\d.]+)\s*([KMGTP]?i?B))?""")
        private val PLAIN_SPEED_REGEX = Regex("""\bat\s+~?\s*([\d.]+)\s*([KMGTP]?i?B)/s""")
        private val ARIA2C_REGEX =
            Regex("""^\[#\w+\s+([\d.]+)\s*([KMGTP]?i?B)/([\d.]+)\s*([KMGTP]?i?B)""")
        private val ARIA2C_SPEED_REGEX = Regex("""DL:\s*([\d.]+)\s*([KMGTP]?i?B)""")

        private val POST_PROCESSOR_TAGS =
            setOf(
                "ExtractAudio",
                "VideoRemuxer",
                "VideoConvertor",
                "EmbedSubtitle",
                "EmbedThumbnail",
                "Metadata",
                "ThumbnailsConvertor",
                "SubtitlesConvertor",
                "SponsorBlock",
                "ModifyChapters",
                "SplitChapters",
                "MoveFiles",
            )

        private fun parseSize(value: String, unit: String): Double? {
            if (value.isEmpty()) return null
            val number = value.toDoubleOrNull() ?: return null
            val multiplier =
                when (unit.firstOrNull()?.uppercaseChar()) {
                    'K' -> 1024.0
                    'M' -> 1024.0 * 1024
                    'G' -> 1024.0 * 1024 * 1024
                    'T' -> 1024.0 * 1024 * 1024 * 1024
                    'P' -> 1024.0 * 1024 * 1024 * 1024 * 1024
                    else -> 1.0
                }
            return number * multiplier
        }

        private fun formatSpeed(bytesPerSecond: Double): String =
            when {
                bytesPerSecond >= 1024.0 * 1024 * 1024 ->
                    "%.1f GBps".format(Locale.US, bytesPerSecond / (1024.0 * 1024 * 1024))
                bytesPerSecond >= 1024.0 * 1024 ->
                    "%.1f MBps".format(Locale.US, bytesPerSecond / (1024.0 * 1024))
                bytesPerSecond >= 1024.0 ->
                    "%.1f KBps".format(Locale.US, bytesPerSecond / 1024.0)
                else -> "%.1f Bps".format(Locale.US, bytesPerSecond)
            }
    }
}
