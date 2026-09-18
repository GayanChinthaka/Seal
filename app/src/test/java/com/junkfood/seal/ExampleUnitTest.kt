package com.junkfood.seal

import com.junkfood.seal.util.connectWithDelimiter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testTextJoin() {
        assertEquals(
            connectWithDelimiter("123", "456", "789", delimiter = ","),
            listOf(123, 456, 789).joinToString(separator = ",") { it.toString() },
        )
        assertEquals(connectWithDelimiter(delimiter = ","), "")
        assertEquals(emptyList<String>().joinToString(separator = ",") { it }, "")
    }

    @Test
    fun testParseSpeedFromText() {
        assertEquals(
            "1.5 MBps",
            com.junkfood.seal.util.parseSpeedFromText(
                "[download]   2.3% of 100.00MiB at 1.50MiB/s ETA 01:05"
            ),
        )
        assertEquals(
            "500.0 KBps",
            com.junkfood.seal.util.parseSpeedFromText(
                "[download]  10.0% of ~ 80.00MiB at  500.00KiB/s ETA 00:30"
            ),
        )
        assertEquals(
            "10.5 MBps",
            com.junkfood.seal.util.parseSpeedFromText(
                "[download]   0.5% of 1.23GiB at 10.50MiB/s ETA 02:00"
            ),
        )
        assertEquals(
            "2.5 MBps",
            com.junkfood.seal.util.parseSpeedFromText(
                "[#123456 12MiB/34MiB(35%) CN:4 DL:2.5MiB ETA:8s]"
            ),
        )
        assertEquals(
            null,
            com.junkfood.seal.util.parseSpeedFromText(
                "[download]   0.5% of  10.00MiB at   Unknown B/s ETA Unknown"
            ),
        )
        assertEquals(
            null,
            com.junkfood.seal.util.parseSpeedFromText("[download] Destination: video.mp4"),
        )
    }

    @Test
    fun testParseFileSizeFromText() {
        val bytes =
            com.junkfood.seal.util.parseFileSizeFromText(
                "[download]   2.3% of 100.00MiB at 1.50MiB/s ETA 01:05"
            )
        assertEquals(100.0 * 1024 * 1024, bytes ?: 0.0, 0.001)

        val approxBytes =
            com.junkfood.seal.util.parseFileSizeFromText(
                "[download]  10.0% of ~ 80.00MiB at  500.00KiB/s ETA 00:30"
            )
        assertEquals(80.0 * 1024 * 1024, approxBytes ?: 0.0, 0.001)

        val ariaBytes =
            com.junkfood.seal.util.parseFileSizeFromText(
                "[#123456 12MiB/34MiB(35%) CN:4 DL:2.5MiB ETA:8s]"
            )
        assertEquals(34.0 * 1024 * 1024, ariaBytes ?: 0.0, 0.001)
    }

    @Test
    fun testSanitizeBinaryUnits() {
        assertEquals(
            "[download]   2.3% of 100.00MB at 1.50MB/s ETA 01:05",
            com.junkfood.seal.util.sanitizeBinaryUnits(
                "[download]   2.3% of 100.00MiB at 1.50MiB/s ETA 01:05"
            ),
        )
        assertEquals(
            "[download]  10.0% of ~ 80.00MB at  500.00KB/s ETA 00:30",
            com.junkfood.seal.util.sanitizeBinaryUnits(
                "[download]  10.0% of ~ 80.00MiB at  500.00KiB/s ETA 00:30"
            ),
        )
        assertEquals(
            "[#123456 12MB/34MB(35%) CN:4 DL:2.5MB ETA:8s]",
            com.junkfood.seal.util.sanitizeBinaryUnits(
                "[#123456 12MiB/34MiB(35%) CN:4 DL:2.5MiB ETA:8s]"
            ),
        )
        assertEquals("1.2GB", com.junkfood.seal.util.sanitizeBinaryUnits("1.2GiB"))
    }
}
