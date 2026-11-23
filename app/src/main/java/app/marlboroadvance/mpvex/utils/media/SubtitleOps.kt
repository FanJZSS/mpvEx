package app.marlboroadvance.mpvex.utils.media

import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.Locale

/**
 * Automatically loads the subtitle files that belong to the playing video.
 *
 * Works for local files and for SMB / FTP / WebDAV files played through
 * [NetworkStreamingProxy]: the video's directory is listed and every related subtitle is attached
 * through the local proxy, so seeking and track switching behave like local playback.
 *
 * Association rules, evaluated per file in the same directory:
 *
 *  1. same base name: `Movie.ass`, `Movie.mkv` + `Movie.srt`
 *  2. same base name plus a language / flag / part suffix: `Movie.zh-CN.ass`, `Movie.简体.ass`,
 *     `Movie.2.ass`, `Movie - Dialogue.ass`
 *  3. release-metadata names: `[字幕组].ass`, `[VCB-Studio] Movie [Ma10p].ass`, `ABCD1234.ass`
 *
 * Track priority: ASS/SSA first (styles, karaoke, signs are preserved and the first ASS becomes the
 * selected track), then plain text subtitles as selectable alternatives - the first text track is
 * only auto-selected when no ASS file matched.
 *
 * Danmaku (`danmaku.xml`, `danmaku_ass_danmaku.xml`, `danmaku_bilibili_cid_*.xml`) from 弹弹play /
 * the mpv danmaku plugins is attached as a **secondary** subtitle track when
 * [app.marlboroadvance.mpvex.preferences.SubtitlesPreferences.autoloadDanmaku] is enabled, so the
 * ASS dialogue and the floating danmaku stay visible at the same time. When several pools exist the
 * one whose encoded duration is closest to the video wins (see [DanmakuXml]).
 *
 * Fonts sitting next to the video are forwarded as well (`sub-fonts-add`) so ASS styles whose font
 * names are not installed on the device still render correctly.
 */
object SubtitleOps : KoinComponent {
  private const val TAG = "SubtitleOps"
  private val networkRepository: NetworkRepository by inject()

  private val ASS_EXTENSIONS = setOf("ass", "ssa")
  private val DANMAKU_FILE_NAMES = setOf("danmaku.xml")

  private fun shouldSkipNetworkSubtitleAutoload(
    videoFilePath: String,
    videoFileName: String,
  ): Boolean {
    val p = videoFilePath.lowercase(Locale.getDefault())
    val n = videoFileName.lowercase(Locale.getDefault())

    val looksLikePlaylist =
      p.endsWith(".m3u") || p.endsWith(".m3u8") ||
        p.contains(".m3u?") || p.contains(".m3u8?") ||
        n.endsWith(".m3u") || n.endsWith(".m3u8")

    val genericName = n.isBlank() || n == "network stream"

    return looksLikePlaylist || genericName
  }

  suspend fun autoloadSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long = -1L,
    autoloadDanmaku: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    try {
      // Skip file descriptor URIs (these don't have a parent directory concept)
      if (videoFilePath.startsWith("fd://")) return@withContext

      // For content:// URIs, we can't autoload (no access to parent directory)
      if (videoFilePath.startsWith("content://")) return@withContext

      // Check if this is a network file with connection ID (SMB/FTP/WebDAV via proxy)
      if (networkConnectionId != -1L) {
        autoloadNetworkFileSubtitles(
          videoFilePath = videoFilePath,
          videoFileName = videoFileName,
          networkConnectionId = networkConnectionId,
          autoloadDanmaku = autoloadDanmaku,
        )
        return@withContext
      }

      // Check if this is a network stream (http, https, ftp, ftps, smb, webdav, etc.)
      val isNetworkStream = videoFilePath.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))

      if (isNetworkStream) {
        if (shouldSkipNetworkSubtitleAutoload(videoFilePath, videoFileName)) {
          Log.d(TAG, "Skipping network subtitle autoload for: $videoFilePath")
          return@withContext
        }
        // For plain network streams, try to load subtitles with common extensions
        autoloadNetworkSubtitles(videoFilePath, videoFileName)
      } else {
        // For local files, scan the directory
        autoloadLocalSubtitles(videoFilePath, videoFileName, autoloadDanmaku)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error loading subtitles", e)
    }
  }

  /**
   * Autoload subtitles for network files (SMB/FTP/WebDAV).
   *
   * Lists the directory of the video and loads every subtitle file that belongs to it, matching
   * by name the same way as for local files. Plain text subtitles are attached with the `auto`
   * flag, ASS/SSA subtitles with `select`, so a styled ASS track wins over a bare SRT.
   *
   * ASS styles reference font names, not files, so every font file found in the same directory
   * is also registered so that `\fn` lookups can resolve them.
   */
  private suspend fun autoloadNetworkFileSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long,
    autoloadDanmaku: Boolean = false,
  ) {
    try {
      Log.d(TAG, "Autoloading subtitles for network file: $videoFilePath")

      val connection = networkRepository.getConnectionById(networkConnectionId)
      if (connection == null) {
        Log.w(TAG, "Network connection not found: $networkConnectionId")
        return
      }

      // Get the directory path (parent of the video file)
      val directoryPath = videoFilePath.substringBeforeLast('/', "")
      if (directoryPath.isEmpty()) {
        Log.w(TAG, "Could not determine directory path from: $videoFilePath")
        return
      }

      Log.d(TAG, "Scanning directory: $directoryPath")

      val filesResult = networkRepository.listFiles(connection, directoryPath)
      if (filesResult.isFailure) {
        Log.w(TAG, "Failed to list network directory: ${filesResult.exceptionOrNull()?.message}")
        return
      }

      val files = filesResult.getOrNull() ?: emptyList()
      val regularFiles = files.filter { !it.isDirectory }

      // Forward fonts so ASS subtitles can resolve font names that live next to the video.
      // Fonts are only needed for styled ASS/SSA tracks; skip the extra network streams otherwise.
      val normalizedVideoBase = normalizeName(videoFileName.substringBeforeLast('.'))
      val hasMatchingAss = regularFiles.any {
        isAssFile(it.name) && isRelatedSubtitle(normalizedVideoBase, it.name)
      }
      if (hasMatchingAss) {
        regularFiles
          .filter { isFontFile(it.name) }
          .forEach { font ->
            runCatching {
              val url = registerProxyStream(connection, font.path, font.size, "font/ttf")
              MPVLib.command("sub-fonts-add", url)
              Log.d(TAG, "Added network font: ${font.name}")
            }.onFailure { e ->
              Log.d(TAG, "Could not add network font ${font.name}: ${e.message}")
            }
          }
      }

      val uploadTimeMs = runCatching { MPVLib.getPropertyDouble("duration")?.times(1000)?.toLong() ?: 0L }
        .getOrDefault(0L)

      val matched = matchSubtitles(
        videoFileName = videoFileName,
        candidates = regularFiles.map { it.name },
        autoloadDanmaku = autoloadDanmaku,
        uploadTimeMs = uploadTimeMs,
      )

      if (matched.isEmpty()) {
        Log.d(TAG, "No matching subtitle files found for: $videoFileName")
        return
      }

      Log.d(TAG, "Found ${matched.size} subtitle file(s): ${matched.joinToString { it.fileName }}")

      val proxy = NetworkStreamingProxy.getInstance()
      var selectedTitle: String? = null
      matched.forEach { match ->
        val networkFile = regularFiles.firstOrNull { it.name == match.fileName } ?: return@forEach
        try {
          val displayName = match.fileName

          // Create a URL-safe stream id, keeping the extension so mpv can detect the format
          val urlSafeFilename = proxySafeName(displayName)

          val proxyUrl = proxy.registerStream(
            streamId = urlSafeFilename,
            connection = connection,
            filePath = networkFile.path,
            fileSize = networkFile.size,
            mimeType = "text/plain",
          )

          // MPV command format: sub-add <url> [<flags> [<title>]]
          // The selected/primary track ids are captured right away because danmaku may move
          // the primary track to the secondary slot below.
          val trackCountBefore = MPVLib.getPropertyInt("track-list/count") ?: 0
          if (match.flag == "select") selectedTitle = displayName
          MPVLib.command("sub-add", proxyUrl, match.flag, displayName)

          val trackCountAfter = MPVLib.getPropertyInt("track-list/count") ?: 0
          if (trackCountAfter > trackCountBefore) {
            Log.d(TAG, "Loaded network subtitle: '$displayName' via proxy (flag=${match.flag})")
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load subtitle ${match.fileName}: ${e.message}", e)
        }
      }

      ensureExternalSubtitleSelected(matched, selectedTitle)
      promoteDanmakuToSecondary(matched)
    } catch (e: Exception) {
      Log.e(TAG, "Error autoloading network subtitles", e)
    }
  }

  private fun registerProxyStream(
    connection: NetworkConnection,
    filePath: String,
    fileSize: Long,
    mimeType: String,
  ): String {
    val streamId = filePath.substringAfterLast('/').let { proxySafeName(it) }
    return NetworkStreamingProxy.getInstance().registerStream(
      streamId = streamId,
      connection = connection,
      filePath = filePath,
      fileSize = fileSize,
      mimeType = mimeType,
    )
  }

  private fun proxySafeName(name: String): String {
    val safe = name.replace(" ", ".").replace(Regex("[^a-zA-Z0-9._-]"), "")
    return safe.ifBlank { "file_${System.currentTimeMillis()}" }
  }

  internal data class SubtitleMatch(
    val fileName: String,
    val flag: String,
    val isAss: Boolean,
    val isDanmaku: Boolean,
  )

  /**
   * Pure matching logic shared by the local and network paths.
   *
   * @param videoFileName name of the playing video, with extension
   * @param candidates names of the files in the same directory
   * @param autoloadDanmaku whether `danmaku.xml` should be loaded as a secondary subtitle
   * @param uploadTimeMs duration of the video in ms, used to pick the best danmaku pool
   * @param danmakuPathOf resolves a candidate name to a local path for metadata sniffing;
   *   network candidates have no local path and fall back to the file name heuristics
   */
  internal fun matchSubtitles(
    videoFileName: String,
    candidates: List<String>,
    autoloadDanmaku: Boolean,
    uploadTimeMs: Long = 0L,
    danmakuPathOf: (String) -> String? = { null },
  ): List<SubtitleMatch> {
    val baseName = videoFileName.substringBeforeLast('.').ifBlank { videoFileName }
    val normalizedBase = normalizeName(baseName)

    val subtitles = candidates
      .distinct()
      .filter { isSubtitleFile(it) && !isDanmakuFile(it) }
      .filter { isRelatedSubtitle(normalizedBase, it) }

    if (subtitles.isEmpty()) {
      // Even without a matching subtitle we may still want danmaku
      return if (autoloadDanmaku) {
        pickDanmaku(candidates, uploadTimeMs, danmakuPathOf)?.let { listOf(it) } ?: emptyList()
      } else {
        emptyList()
      }
    }

    val assFiles = subtitles.filter { isAssFile(it) }.sortedBy { it.lowercase() }
    val textFiles = subtitles.filterNot { isAssFile(it) }.sortedBy { it.lowercase() }

    val result = mutableListOf<SubtitleMatch>()

    // ASS/SSA first (styled, karaoke, signs) - the first one becomes the selected track
    assFiles.forEachIndexed { index, name ->
      result += SubtitleMatch(
        fileName = name,
        flag = if (index == 0) "select" else "auto",
        isAss = true,
        isDanmaku = false,
      )
    }

    // Plain text subtitles stay available as alternatives without stealing selection.
    // With no ASS track at all, the first one takes over as the selected subtitle.
    textFiles.forEachIndexed { index, name ->
      result += SubtitleMatch(
        fileName = name,
        flag = if (assFiles.isEmpty() && index == 0) "select" else "auto",
        isAss = false,
        isDanmaku = false,
      )
    }

    if (autoloadDanmaku) {
      pickDanmaku(candidates, uploadTimeMs, danmakuPathOf)?.let { result += it }
    }

    return result
  }

  private fun pickDanmaku(
    candidates: List<String>,
    uploadTimeMs: Long,
    danmakuPathOf: (String) -> String? = { null },
  ): SubtitleMatch? {
    val danmakuFiles = candidates.filter { isDanmakuFile(it) }
    if (danmakuFiles.isEmpty()) return null

    val chosen = danmakuFiles.maxByOrNull { name ->
      DanmakuXml.scoreForDuration(name, uploadTimeMs, danmakuPathOf(name))
    } ?: return null

    Log.d(TAG, "Selected danmaku file: $chosen (video duration ${uploadTimeMs}ms)")
    return SubtitleMatch(
      fileName = chosen,
      // Load as secondary track so the authoritative ASS track keeps its own styles/position
      flag = if (chosen.lowercase().startsWith("secondary")) "select" else "auto",
      isAss = false,
      isDanmaku = true,
    )
  }

  private suspend fun autoloadLocalSubtitles(
    videoFilePath: String,
    videoFileName: String,
    autoloadDanmaku: Boolean = false,
  ) {
    val videoFile = File(videoFilePath)
    val videoDirectory = videoFile.parentFile ?: return

    val files = videoDirectory.listFiles()?.filter { it.isFile } ?: emptyList()
    val filesByName = files.associateBy { it.name }

    // Forward local fonts so ASS subtitles can resolve font names
    files
      .filter { isFontFile(it.name) }
      .forEach { font ->
        runCatching {
          MPVLib.command("sub-fonts-add", font.absolutePath)
        }
      }

    val uploadTimeMs = runCatching { MPVLib.getPropertyDouble("duration")?.times(1000)?.toLong() ?: 0L }
      .getOrDefault(0L)

    val matched = matchSubtitles(
      videoFileName = videoFileName,
      candidates = files.map { it.name },
      autoloadDanmaku = autoloadDanmaku,
      uploadTimeMs = uploadTimeMs,
      danmakuPathOf = { filesByName[it]?.absolutePath },
    )

    if (matched.isEmpty()) return

    matched.forEach { match ->
      val subtitle = filesByName[match.fileName] ?: return@forEach
      // MPV command format: sub-add <url> [<flags> [<title>]]
      MPVLib.command("sub-add", subtitle.absolutePath, match.flag, subtitle.name)
      Log.d(TAG, "Loaded local subtitle: ${subtitle.name} (flag=${match.flag})")
    }

    ensureExternalSubtitleSelected(matched)
    promoteDanmakuToSecondary(matched)
  }

  /**
   * When nothing ended up selected, make sure the best external subtitle is actually displayed.
   * ASS subtitles win over plain text ones. Danmaku is never promoted to the primary slot.
   *
   * @param selectedTitle source of the subtitle expected to be selected, when known
   */
  private fun ensureExternalSubtitleSelected(
    matched: List<SubtitleMatch>,
    selectedTitle: String? = null,
  ) {
    if (matched.isEmpty()) return
    val currentSid = MPVLib.getPropertyInt("sid") ?: 0
    if (currentSid > 0) return

    val preferredTitle = selectedTitle
      ?: matched.firstOrNull { it.flag == "select" && !it.isDanmaku }?.fileName
      ?: matched.firstOrNull { !it.isDanmaku }?.fileName
      ?: return

    // Find the freshly added external track by its title
    val count = MPVLib.getPropertyInt("track-list/count") ?: 0
    for (index in 0 until count) {
      val title = MPVLib.getPropertyString("track-list/$index/title") ?: continue
      val type = MPVLib.getPropertyString("track-list/$index/type")
      if (type == "sub" && title == preferredTitle) {
        val id = MPVLib.getPropertyInt("track-list/$index/id") ?: continue
        MPVLib.setPropertyInt("sid", id)
        Log.d(TAG, "Forced external subtitle '$preferredTitle' (id=$id) as active track")
        return
      }
    }
  }

  /**
   * mpv can show two subtitle tracks at once. When danmaku is loaded next to an ASS subtitle, the
   * ASS track stays primary (keeping its own styles and position) and the danmaku track is drawn
   * as the secondary subtitle.
   */
  private fun promoteDanmakuToSecondary(matched: List<SubtitleMatch>) {
    val danmaku = matched.firstOrNull { it.isDanmaku } ?: return
    val hasAss = matched.any { it.isAss }
    if (!hasAss) return

    val count = MPVLib.getPropertyInt("track-list/count") ?: 0
    for (index in 0 until count) {
      val title = MPVLib.getPropertyString("track-list/$index/title") ?: continue
      val type = MPVLib.getPropertyString("track-list/$index/type")
      if (type == "sub" && title == danmaku.fileName) {
        val id = MPVLib.getPropertyInt("track-list/$index/id") ?: continue
        MPVLib.setPropertyInt("secondary-sid", id)
        Log.d(TAG, "Danmaku '${danmaku.fileName}' (id=$id) shown as secondary subtitle")
        return
      }
    }
  }

  private suspend fun autoloadNetworkSubtitles(
    videoFilePath: String,
    videoFileName: String,
  ) {
    // Get base name without extension
    val baseName = videoFileName.substringBeforeLast('.')

    // Get the base URL (path without the filename)
    val lastSlashIndex = videoFilePath.lastIndexOf('/')
    if (lastSlashIndex == -1) return

    val baseUrl = videoFilePath.substring(0, lastSlashIndex + 1)

    // Try ASS first so it becomes the selected track, then common text formats
    val subtitleExtensions = listOf("ass", "ssa", "srt", "vtt", "sub", "sup")

    subtitleExtensions.forEachIndexed { index, ext ->
      val subtitleUrl = "$baseUrl$baseName.$ext"
      try {
        // Use "auto" for the alternates, "select" only for the first found (.ass)
        val flag = if (index == 0) "select" else "auto"
        MPVLib.command("sub-add", subtitleUrl, flag, "$baseName.$ext")
        Log.d(TAG, "Attempting to load network subtitle: $subtitleUrl (flag=$flag)")
      } catch (e: Exception) {
        Log.d(TAG, "Could not load network subtitle $subtitleUrl: ${e.message}")
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Name matching helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Normalize a name for comparison: lowercase, unify extension separators, collapse spaces.
   */
  private fun normalizeName(name: String): String =
    name
      .lowercase(Locale.ROOT)
      .replace('_', ' ')
      .replace(Regex("\\s+"), " ")
      .trim()

  /**
   * True when [subtitleFileName] looks like it belongs to [normalizedBase] (the normalized name of
   * the video without extension).
   */
  internal fun isRelatedSubtitle(normalizedBase: String, subtitleFileName: String): Boolean {
    if (normalizedBase.isBlank()) return false
    val subBase = normalizeName(subtitleFileName.substringBeforeLast('.'))
    if (subBase.isBlank()) return false

    // Exact same name: Movie.mkv + Movie.ass
    if (subBase == normalizedBase) return true

    // Same name with a suffix: Movie.zh-CN.ass, Movie - 简体.ass, Movie.2.ass
    // ("$" needed before '_' so Kotlin does not read it as part of the identifier)
    if (subBase.startsWith("$normalizedBase ") || subBase.startsWith("$normalizedBase.") ||
      subBase.startsWith("$normalizedBase-") || subBase.startsWith("${normalizedBase}_") ||
      subBase.startsWith("$normalizedBase[") || subBase.startsWith("$normalizedBase(") ||
      subBase.startsWith("$normalizedBase【")
    ) {
      return isAcceptableSuffix(subBase.substring(normalizedBase.length))
    }

    // Tag-only name, two accepted shapes:
    //  - release group / flag wrapped in brackets: `[字幕组].ass`, `[CHS].ass`
    //  - a CRC-like hex chunk: `ABCD1234.ass`
    // A bare word (`Other.ass`, `abc.ass`) is never treated as belonging to the video.
    val subBaseRaw = subtitleFileName.substringBeforeLast('.').trim()
    return when {
      subBaseRaw.length >= 2 &&
        subBaseRaw.first() in BRACKET_OPEN &&
        subBaseRaw.last() in BRACKET_CLOSE -> true

      subBaseRaw.matches(CRC_PATTERN) -> true

      else -> false
    }
  }

  /**
   * Decide whether the text after the base name looks like a language/flag/part token rather than
   * another movie or an epilogue of the title.
   *
   * Accepted: empty, a part number (`Movie.2.ass`), language / flag words (`Movie.zh-CN.ass`,
   * `Movie - 简体.ass`). Rejected: prose or an unrelated name (`Movie Collection.ass`).
   */
  private fun isAcceptableSuffix(rawSuffix: String): Boolean {
    val suffix = rawSuffix.trim(' ', '.', '-', '_', '[', ']', '(', ')', '【', '】')
    if (suffix.isEmpty()) return true
    if (suffix.length > 40) return false

    // Pure index / part number: Movie.2.ass, Movie - 3.ass
    if (suffix.matches(Regex("^\\d{1,3}$"))) return true

    // Joined language lists: 简体&英文, 简中/繁中
    if (suffix.split('&', '/', '+').all { isCommonSubTag(it) }) return true

    val tokens = suffix.split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
    return tokens.isNotEmpty() && tokens.all { isCommonSubTag(it) }
  }

  private val COMMON_SUB_TAGS = setOf(
    // Chinese variants
    "zh", "zhs", "zht", "chi", "cn", "sc", "tc", "gb", "gbk", "big5", "hans", "hant",
    "简", "繁", "简体", "繁体", "简中", "繁中", "中", "中文", "中英", "简繁", "双语", "双字", "字幕",
    "简体中文", "繁体中文", "国产", "内嵌", "外挂", "双语字幕", "字幕组",
    "hk", "tw", "sg", "mo",
    // Major languages
    "en", "eng", "english", "jp", "jpn", "ja", "japanese", "ko", "kor", "korean",
    "fr", "fre", "frn", "french", "de", "ger", "deu", "german", "es", "spa", "spanish",
    "it", "ita", "italian", "ru", "rus", "russian", "pt", "por", "portuguese",
    "th", "tha", "viet", "vi", "id", "ind", "ms", "may", "ar", "ara", "he", "heb",
    // Flags commonly appended to subtitle files
    "default", "forced", "sdh", "cc", "full", "signs", "songs", "dialogue", "commentary",
    "simplified", "traditional", "sub", "subs", "subtitle", "subtitles", "official",
  )

  /** Opening / closing brackets used by release groups to wrap tags: `[字幕组]`, `【CHS】`. */
  private val BRACKET_OPEN = setOf('[', '【', '(', '{')
  private val BRACKET_CLOSE = setOf(']', '】', ')', '}')

  /** Release-group CRC chunk: `ABCD1234`. */
  private val CRC_PATTERN = Regex("[0-9A-Fa-f]{8}")
  /**
   * A token only counts as a subtitle tag when it is a known language / flag, or a region form of
   * one (`zh-cn`, `pt-br`, `en-us`). Arbitrary short words are NOT tags, otherwise `Movie.Pro.ass`
   * or `Movie.mkv` + `The.ass` would match.
   */
  private fun isCommonSubTag(token: String): Boolean {
    val t = token.trim().lowercase(Locale.ROOT)
    if (t.isEmpty()) return false
    if (t in COMMON_SUB_TAGS) return true

    // Region forms: en-us, pt-br, zh-hans, zh_cn
    val parts = t.split('-', '_').filter { it.isNotEmpty() }
    return parts.size > 1 && parts.first() in COMMON_SUB_TAGS && parts.drop(1).all {
      it in COMMON_SUB_TAGS || it.matches(Regex("[a-z]{2}|[a-z]{4}|\\d{3}"))
    }
  }

  /**
   * Split a name into lowercase alphanumeric tokens: `[VCB-Studio] Movie [Ma10p]` ->
   * `[vcb, studio, movie, ma10p]`.
   */
  private fun tagTokens(text: String): Set<String> =
    Regex("[a-z0-9\\u4e00-\\u9fff]+")
      .findAll(text)
      .map { it.value }
      .filter { it.isNotBlank() }
      .toSet()

  // ---------------------------------------------------------------------------------------------
  // File type helpers
  // ---------------------------------------------------------------------------------------------

  private fun isAssFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in ASS_EXTENSIONS
  }

  private fun isDanmakuFile(fileName: String): Boolean {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
    return name in DANMAKU_FILE_NAMES ||
      (name.startsWith("danmaku") && name.endsWith(".xml")) ||
      name.endsWith(".danmaku.xml")
  }

  private fun isFontFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in setOf("ttf", "otf", "ttc", "otc")
  }

  private fun isSubtitleFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return extension in setOf(
      // Common & modern
      "srt", "vtt", "ass", "ssa",
      // DVD / Blu-ray
      "sub", "idx", "sup",
      // Streaming / XML / Professional
      "xml", "ttml", "dfxp", "itt", "ebu", "imsc", "usf",
      // Online platforms
      "sbv", "srv1", "srv2", "srv3", "json",
      // Legacy & niche
      "sami", "smi", "mpl", "pjs", "stl", "rt", "psb", "cap",
      // Broadcast captions
      "scc", "vttx",
      // Karaoke / lyrics
      "lrc", "krc",
      // Fallback / raw text
      "txt", "pgs"
    )
  }
}
