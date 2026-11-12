package com.junkfood.seal

import com.junkfood.seal.download.DownloadProgressTracker
import com.junkfood.seal.download.DownloadProgressTracker.ExpectedPart
import com.junkfood.seal.download.DownloadProgressTracker.PartKind
import com.junkfood.seal.download.DownloadProgressTracker.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MB = 1024.0 * 1024

class DownloadProgressTrackerTest {
    private val videoAndAudio =
        listOf(
            ExpectedPart(formatId = "137", size = 50 * MB, kind = PartKind.Video),
            ExpectedPart(formatId = "140", size = 4.8 * MB, kind = PartKind.Audio),
        )

    private fun line(
        status: String,
        formatId: String,
        downloaded: String,
        total: String,
        estimate: String = "NA",
        speed: String = "NA",
    ) = "[download]  x% of y [seal]$status|$formatId|$downloaded|$total|$estimate|$speed"

    @Test
    fun addsUpTheFilesOfOneTask() {
        val tracker = DownloadProgressTracker(videoAndAudio)

        tracker.onLine("[download] Destination: /tmp/video.f137.mp4")
        var snapshot =
            tracker.onLine(
                line("downloading", "137", "${25 * MB}", "${50 * MB}", speed = "${1.5 * MB}")
            )
        assertEquals(25 * MB, snapshot.downloadedBytes, 1.0)
        assertEquals(54.8 * MB, snapshot.totalBytes, 1.0)
        assertEquals(25 / 54.8, snapshot.progress.toDouble(), 0.001)
        assertEquals(1, snapshot.partIndex)
        assertEquals(2, snapshot.partCount)
        assertEquals(PartKind.Video, snapshot.partKind)
        assertEquals("1.5 MBps", snapshot.speedText)

        tracker.onLine(line("finished", "137", "${50 * MB}", "${50 * MB}"))
        tracker.onLine("[download] Destination: /tmp/video.f140.m4a")
        snapshot = tracker.onLine(line("downloading", "140", "${2.4 * MB}", "${4.8 * MB}"))
        assertEquals(52.4 * MB, snapshot.downloadedBytes, 1.0)
        assertEquals(54.8 * MB, snapshot.totalBytes, 1.0)
        assertEquals(2, snapshot.partIndex)
        assertEquals(PartKind.Audio, snapshot.partKind)

        snapshot = tracker.onLine(line("finished", "140", "${4.8 * MB}", "${4.8 * MB}"))
        assertEquals(1f, snapshot.progress, 0.0001f)
    }

    @Test
    fun reportsMergingAndProcessing() {
        val tracker = DownloadProgressTracker(videoAndAudio)
        // Runs before the download and must not count as post-processing
        assertEquals(
            Phase.Downloading,
            tracker.onLine("[SponsorBlock] Fetching SponsorBlock segments").phase,
        )

        tracker.onLine("[download] Destination: /tmp/video.f137.mp4")
        tracker.onLine(line("finished", "137", "${50 * MB}", "${50 * MB}"))
        tracker.onLine("[download] Destination: /tmp/video.f140.m4a")
        tracker.onLine(line("finished", "140", "${4.8 * MB}", "${4.8 * MB}"))

        var snapshot = tracker.onLine("[Merger] Merging formats into \"/tmp/video.mp4\"")
        assertEquals(Phase.Merging, snapshot.phase)
        assertEquals(0, snapshot.partIndex)
        assertEquals("", snapshot.speedText)

        snapshot = tracker.onLine("[Metadata] Adding metadata to \"/tmp/video.mp4\"")
        assertEquals(Phase.Processing, snapshot.phase)
        assertEquals(1f, snapshot.progress, 0.0001f)
    }

    @Test
    fun usesTheSizeEstimateOfFragmentedDownloads() {
        val tracker = DownloadProgressTracker(listOf(ExpectedPart("hls-1080", 0.0, PartKind.Video)))
        tracker.onLine("[download] Destination: /tmp/video.mp4")

        // The first estimate is far too high, as with the 193.81MB shown for a 54.8MB video
        var snapshot =
            tracker.onLine(line("downloading", "hls-1080", "${2 * MB}", "NA", "${193.81 * MB}"))
        assertEquals(193.81 * MB, snapshot.totalBytes, 1.0)

        snapshot = tracker.onLine(line("downloading", "hls-1080", "${30 * MB}", "NA", "${55 * MB}"))
        assertEquals(55 * MB, snapshot.totalBytes, 1.0)
        assertEquals(30 * MB, snapshot.downloadedBytes, 1.0)

        snapshot = tracker.onLine(line("finished", "hls-1080", "${54.8 * MB}", "${54.8 * MB}"))
        assertEquals(54.8 * MB, snapshot.totalBytes, 1.0)
        assertEquals(1f, snapshot.progress, 0.0001f)
    }

    @Test
    fun resumedFileCountsTheBytesAlreadyOnDisk() {
        val tracker = DownloadProgressTracker(videoAndAudio)
        tracker.onLine("[download] /tmp/video.f137.mp4 has already been downloaded")
        tracker.onLine(line("finished", "137", "NA", "${50 * MB}"))
        tracker.onLine("[download] Destination: /tmp/video.f140.m4a")
        val snapshot = tracker.onLine(line("downloading", "140", "${1.2 * MB}", "${4.8 * MB}"))
        assertEquals(51.2 * MB, snapshot.downloadedBytes, 1.0)
        assertEquals(51.2 / 54.8, snapshot.progress.toDouble(), 0.001)
    }

    @Test
    fun progressDoesNotGoBackwards() {
        val tracker = DownloadProgressTracker(listOf(ExpectedPart("hls", 0.0, PartKind.Video)))
        tracker.onLine("[download] Destination: /tmp/video.mp4")
        val first = tracker.onLine(line("downloading", "hls", "${10 * MB}", "NA", "${20 * MB}"))
        val second = tracker.onLine(line("downloading", "hls", "${11 * MB}", "NA", "${40 * MB}"))
        assertEquals(0.5f, first.progress, 0.001f)
        assertEquals(0.5f, second.progress, 0.001f)
    }

    @Test
    fun startsOverWhenYtDlpExtractsAgain() {
        val tracker = DownloadProgressTracker(videoAndAudio)
        tracker.onLine("[download] Destination: /tmp/video.f137.mp4")
        tracker.onLine(line("downloading", "137", "${25 * MB}", "${50 * MB}"))
        tracker.onLine("[youtube] Extracting URL: https://www.youtube.com/watch?v=x")
        tracker.onLine("[download] Destination: /tmp/video.f137.mp4")
        val snapshot = tracker.onLine(line("downloading", "137", "${26 * MB}", "${50 * MB}"))
        assertEquals(26 * MB, snapshot.downloadedBytes, 1.0)
        assertEquals(54.8 * MB, snapshot.totalBytes, 1.0)
    }

    @Test
    fun parsesPlainProgressTextWithoutTheTemplate() {
        val tracker = DownloadProgressTracker()
        tracker.onLine("[download] Destination: video.f137.mp4")
        var snapshot =
            tracker.onLine("[download]  50.0% of   50.00MiB at    1.50MiB/s ETA 00:16")
        assertEquals(25 * MB, snapshot.downloadedBytes, 1.0)
        assertEquals(0.5f, snapshot.progress, 0.001f)
        assertEquals("1.5 MBps", snapshot.speedText)

        tracker.onLine("[download] 100% of   50.00MiB in 00:00:33 at 1.50MiB/s")
        tracker.onLine("[download] Destination: video.f140.m4a")
        snapshot = tracker.onLine("[download]  50.0% of    4.00MiB at    1.00MiB/s ETA 00:02")
        assertEquals(52 * MB, snapshot.downloadedBytes, 1.0)
        assertEquals(54 * MB, snapshot.totalBytes, 1.0)

        snapshot =
            tracker.onLine("[#2089b0 400.0KiB/4.0MiB(10%) CN:1 DL:115KiB ETA:30s]")
        assertTrue(snapshot.downloadedBytes > 0)
    }

    @Test
    fun stripsTheFieldsFromTheTextShownToTheUser() {
        assertEquals(
            "[download]  50.0% of 50.00MiB at 1.50MiB/s ETA 00:16",
            DownloadProgressTracker.stripTemplateFields(
                "[download]  50.0% of 50.00MiB at 1.50MiB/s ETA 00:16 [seal]downloading|137|1|2|NA|3"
            ),
        )
    }
}
