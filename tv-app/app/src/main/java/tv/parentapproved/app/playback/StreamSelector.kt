package tv.parentapproved.app.playback

import tv.parentapproved.app.util.AppLogger
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

data class SelectedStream(
    val videoUrl: String,
    val audioUrl: String?,
    val resolution: String,
    val isMerged: Boolean,
)

object StreamSelector {

    fun getAvailableResolutions(
        progressiveStreams: List<VideoStream>,
        videoOnlyStreams: List<VideoStream>,
    ): List<String> {
        val res = (progressiveStreams + videoOnlyStreams)
            .mapNotNull { it.resolution }
            .distinct()
            .sortedByDescending { resolutionToInt(it) }
        return res
    }

    fun selectByResolution(
        targetResolution: String,
        progressiveStreams: List<VideoStream>,
        videoOnlyStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
    ): SelectedStream? {
        // 1. Try progressive matching resolution
        progressiveStreams.find { it.resolution == targetResolution }?.let {
            return SelectedStream(it.content, null, it.resolution, false)
        }

        // 2. Try video-only matching resolution
        val videoOnly = videoOnlyStreams.find { it.resolution == targetResolution }
        val bestAudio = audioStreams.maxByOrNull { it.averageBitrate }

        if (videoOnly != null && bestAudio != null) {
            return SelectedStream(videoOnly.content, bestAudio.content, videoOnly.resolution, true)
        }

        return null
    }

    /**
     * Priority: 1080p > 720p > any progressive > adaptive merge
     */
    fun selectBest(
        progressiveStreams: List<VideoStream>,
        videoOnlyStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
    ): SelectedStream? {
        // Try to get 1080p specifically first (either progressive or merge)
        selectByResolution("1080p", progressiveStreams, videoOnlyStreams, audioStreams)?.let {
            AppLogger.log("Selected: 1080p (Preferred)")
            return it
        }

        // Try progressive first (has audio built in)
        val progressive = progressiveStreams
            .sortedByDescending { resolutionToInt(it.resolution) }

        // Try 720p progressive
        progressive.find { resolutionToInt(it.resolution) == 720 }?.let { stream ->
            AppLogger.log("Selected: 720p progressive")
            return SelectedStream(stream.content, null, stream.resolution ?: "720p", false)
        }

        // Any progressive
        progressive.firstOrNull()?.let { stream ->
            AppLogger.log("Selected: ${stream.resolution} progressive")
            return SelectedStream(stream.content, null, stream.resolution ?: "?", false)
        }

        // Fallback: merge video-only + audio
        val bestAudio = audioStreams.maxByOrNull { it.averageBitrate }
        val bestVideo = videoOnlyStreams
            .sortedByDescending { resolutionToInt(it.resolution) }
            .firstOrNull { resolutionToInt(it.resolution) <= 1080 }

        if (bestVideo != null && bestAudio != null) {
            AppLogger.log("Selected: ${bestVideo.resolution} merged + audio ${bestAudio.averageBitrate}kbps")
            return SelectedStream(bestVideo.content, bestAudio.content, bestVideo.resolution ?: "?", true)
        }

        AppLogger.error("No playable streams found")
        return null
    }

    private fun resolutionToInt(resolution: String?): Int {
        return resolution?.replace("p", "")?.toIntOrNull() ?: 0
    }
}
