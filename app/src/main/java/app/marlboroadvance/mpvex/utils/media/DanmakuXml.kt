package app.marlboroadvance.mpvex.utils.media

import java.util.Locale

/**
 * Helpers for danmaku files produced by DanDanPlay (弹弹play) / mpv 弹幕插件.
 *
 * A folder may hold several danmaku pools for the same video, e.g.
 *
 *  - `danmaku.xml`                 (podcast / raw XML)
 *  - `danmaku_ass_danmaku.xml`     (`<chatserver>` based, duration in `<chatid>`)
 *  - `danmaku_bilibili_cid_123.xml` (`<source>` / `<chatid>` based)
 *
 * The `chatserver` of each file encodes the pool metadata, for example
 * `https://api.dandanplay.net/Danmaku/2/??1408/3600/2020-03-01T12:00:00Z/0` where `3600` is the
 * video duration in seconds. The pool whose duration is closest to the playing video is the most
 * likely to be time-aligned with it, so [scoreForDuration] is used to choose between them.
 *
 * Only the player's title/comment are read: no network access, no user data leaves the device.
 */
object DanmakuXml {
  private const val TAG = "DanmakuXml"

  /** Max bytes read from the file head to sniff the chatserver / duration. */
  private const val SNIFF_BYTES = 16 * 1024

  /**
   * Score for how well a danmaku file fits a video of [videoDurationMs]. Higher is better.
   * A danmaku pool whose `chatserver` duration equals the video gains the most points.
   *
   * @param fileName name of the danmaku file (used for tie-breaking and for the plain `danmaku.xml`
   *   variant, which has no pool metadata)
   * @param videoDurationMs duration of the playing video
   * @param localPath absolute path of the file when it exists locally, used to sniff metadata
   */
  fun scoreForDuration(
    fileName: String,
    videoDurationMs: Long,
    localPath: String? = null,
  ): Double {
    var score = 0.0

    if (videoDurationMs > 0) {
      val poolSeconds = localPath?.takeIf { it.isNotBlank() }?.let { sniffDurationSeconds(it) }
      if (poolSeconds != null && poolSeconds > 0) {
        val videoSeconds = videoDurationMs / 1000.0
        val diff = kotlin.math.abs(poolSeconds - videoSeconds)
        // Exact match: +1000, then decay
        score += 1000.0 / (1.0 + diff)
      }
    }

    val lower = fileName.lowercase(Locale.ROOT)
    // Prefer the plainest names in case of ties
    when {
      lower == "danmaku.xml" -> score += 3.0
      lower.contains("danmaku") -> score += 1.0
    }
    score -= lower.length * 0.001
    return score
  }

  /**
   * Best-effort parse of the pool duration (seconds) from the head of a danmaku XML file by
   * scanning for the `chatserver` element, which DanDanPlay writes as
   * `https://api.dandanplay.net/Danmaku/<pool>/<epId>/<durationSeconds>/<date>/<type>`.
   *
   * @return duration in seconds, or null if it cannot be determined
   */
  fun sniffDurationSeconds(path: String): Long? {
    return try {
      val votes = mutableMapOf<Long, Int>()
      // Read both the file head (chatserver lives early) and the tail (pool metadata packet)
      readTail(path, SNIFF_BYTES)?.let { collectDurationVotes(it, votes) }
      readHead(path, SNIFF_BYTES)?.let { collectDurationVotes(it, votes) }
      votes.maxByOrNull { it.value }?.key
    } catch (e: Exception) {
      android.util.Log.d(TAG, "Could not sniff danmaku duration from $path: ${e.message}")
      null
    }
  }

  private fun collectDurationVotes(text: String, votes: MutableMap<Long, Int>) {
    val pattern = Regex(
      "Danmaku/\\d+/(?:\\d+|\\?\\?|null)*?/(\\d{2,6})/",
      RegexOption.IGNORE_CASE,
    )
    pattern.findAll(text).forEach { match ->
      val seconds = match.groupValues[1].toLongOrNull() ?: return@forEach
      // Plausible video length (2 minutes .. 20 hours); anything else is not a duration
      if (seconds in 120..72_000) {
        votes[seconds] = (votes[seconds] ?: 0) + 1
      }
    }
    // Also accept explicit duration attributes
    Regex("duration\\s*=\\s*\"(\\d{2,6})\"", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
      val seconds = match.groupValues[1].toLongOrNull() ?: return@forEach
      if (seconds in 120..72_000) {
        votes[seconds] = (votes[seconds] ?: 0) + 1
      }
    }
  }

  private fun readHead(path: String, bytes: Int): String? =
    java.io.File(path).inputStream().buffered().use { stream ->
      val buffer = ByteArray(bytes)
      val read = stream.read(buffer)
      if (read <= 0) null else String(buffer, 0, read, Charsets.UTF_8)
    }

  private fun readTail(path: String, bytes: Int): String? {
    val file = java.io.File(path)
    if (!file.exists() || file.length() <= 0) return null
    val length = file.length().coerceAtMost(bytes.toLong()).toInt()
    return java.io.RandomAccessFile(file, "r").use { raf ->
      val start = (file.length() - length).coerceAtLeast(0L)
      raf.seek(start)
      val buffer = ByteArray(length)
      val read = raf.read(buffer)
      if (read <= 0) null else String(buffer, 0, read, Charsets.UTF_8)
    }
  }
}
