package autosubs.core

import java.io.IOException

/** A place to search subtitles. */
interface SubtitleSource {

    /**
     * @param hash     OpenSubtitles hash or null
     * @param size     file size or -1
     * @param fileName video file name or null
     */
    @Throws(IOException::class)
    fun search(id: MediaId, hash: String?, size: Long, fileName: String?, languages: List<String>): List<SubtitleCandidate>

    /** Returns the subtitle file contents (any encoding). */
    @Throws(IOException::class)
    fun download(candidate: SubtitleCandidate): ByteArray

    /** Short name shown in the subtitle list. */
    fun name(): String
}
