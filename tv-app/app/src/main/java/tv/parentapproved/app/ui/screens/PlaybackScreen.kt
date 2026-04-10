package tv.parentapproved.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import tv.parentapproved.app.ServiceLocator
import tv.parentapproved.app.data.ContentSourceRepository
import tv.parentapproved.app.timelimits.TimeLimitStatus
import tv.parentapproved.app.data.events.PlayEventRecorder
import tv.parentapproved.app.data.models.VideoItem
import tv.parentapproved.app.playback.DpadKeyHandler
import tv.parentapproved.app.playback.PlaybackCommand
import tv.parentapproved.app.playback.PlaybackCommandBus
import tv.parentapproved.app.playback.StreamSelector
import tv.parentapproved.app.ui.theme.KidBackground
import tv.parentapproved.app.ui.theme.KidSurface
import tv.parentapproved.app.ui.theme.KidAccent
import tv.parentapproved.app.ui.theme.KidText
import tv.parentapproved.app.ui.theme.KidTextDim
import tv.parentapproved.app.ui.theme.StatusError
import tv.parentapproved.app.ui.theme.StatusWarning
import tv.parentapproved.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(
    videoId: String,
    playlistId: String,
    startIndex: Int,
    onBack: () -> Unit,
    onLocked: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ServiceLocator.database }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentVideoIndex by remember { mutableIntStateOf(startIndex) }
    var playlist by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var playStartTime by remember { mutableStateOf(0L) }
    var playlistTitle by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    var preferredResolution by remember { mutableStateOf<String?>(null) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var seekMessage by remember { mutableStateOf<String?>(null) }
    var controlsTrigger by remember { mutableIntStateOf(0) }

    var availableResolutions by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedResolution by remember { mutableStateOf<String?>(null) }
    var showResolutionPicker by remember { mutableStateOf(false) }
    val resolutionFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showResolutionPicker) {
        if (showResolutionPicker) {
            delay(100)
            try {
                resolutionFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    val streamsRef = remember {
        object {
            var progressive: List<org.schabi.newpipe.extractor.stream.VideoStream> = emptyList()
            var videoOnly: List<org.schabi.newpipe.extractor.stream.VideoStream> = emptyList()
            var audio: List<org.schabi.newpipe.extractor.stream.AudioStream> = emptyList()
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    // Mutable refs for callbacks that need latest state
    val playlistRef = remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    val indexRef = remember { mutableIntStateOf(startIndex) }
    playlistRef.value = playlist
    indexRef.intValue = currentVideoIndex

    // Helper object to break circular references
    val controller = remember {
        object {
            var extractAndPlay: ((String) -> Unit)? = null
            var advanceToNext: (() -> Unit)? = null
            var goToPrev: (() -> Unit)? = null
            var skipAfterDelay: (() -> Unit)? = null
        }
    }

    controller.advanceToNext = {
        val elapsed = ((System.currentTimeMillis() - playStartTime) / 1000).toInt()
        PlayEventRecorder.endEvent(elapsed, 100)

        val nextIndex = indexRef.intValue + 1
        if (nextIndex < playlistRef.value.size) {
            currentVideoIndex = nextIndex
            indexRef.intValue = nextIndex
            controller.extractAndPlay?.invoke(playlistRef.value[nextIndex].videoId)
        } else {
            AppLogger.log("Playlist ended")
            onBack()
        }
    }

    controller.goToPrev = {
        val elapsedSec = ((System.currentTimeMillis() - playStartTime) / 1000).toInt()
        PlayEventRecorder.endEvent(elapsedSec, 0)

        if (elapsedSec > 5) {
            // Restart current video
            controller.extractAndPlay?.invoke(playlistRef.value[indexRef.intValue].videoId)
        } else {
            val prevIndex = indexRef.intValue - 1
            if (prevIndex >= 0) {
                currentVideoIndex = prevIndex
                indexRef.intValue = prevIndex
                controller.extractAndPlay?.invoke(playlistRef.value[prevIndex].videoId)
            } else {
                // Already at first video, restart it
                controller.extractAndPlay?.invoke(playlistRef.value[indexRef.intValue].videoId)
            }
        }
    }

    controller.skipAfterDelay = {
        scope.launch {
            delay(3000)
            controller.advanceToNext?.invoke()
        }
    }

    controller.extractAndPlay = { vid ->
        errorMessage = null
        AppLogger.log("Extracting stream: $vid")
        playStartTime = System.currentTimeMillis()
        availableResolutions = emptyList()
        selectedResolution = null

        val currentVideo = playlistRef.value.getOrNull(indexRef.intValue)
        val title = currentVideo?.title ?: vid
        val durationMs = (currentVideo?.durationSeconds ?: 0) * 1000L

        PlayEventRecorder.startEvent(
            videoId = vid,
            playlistId = playlistId,
            title = title,
            playlistTitle = playlistTitle,
            durationMs = durationMs,
        )

        scope.launch {
            try {
                val streamResult = withContext(Dispatchers.IO) {
                    val url = "https://www.youtube.com/watch?v=$vid"
                    val extractor = ServiceList.YouTube.getStreamExtractor(url)
                    extractor.fetchPage()

                    // Update title from extractor if we only had the video ID
                    val extractedTitle = try { extractor.name } catch (_: Exception) { null }
                    if (!extractedTitle.isNullOrBlank() && extractedTitle != vid) {
                        PlayEventRecorder.updateTitle(extractedTitle)
                    }

                    streamsRef.progressive = (extractor.videoStreams ?: emptyList()).filter { !it.isVideoOnly }
                    streamsRef.videoOnly = extractor.videoOnlyStreams ?: emptyList()
                    streamsRef.audio = extractor.audioStreams ?: emptyList()

                    availableResolutions = StreamSelector.getAvailableResolutions(
                        streamsRef.progressive,
                        streamsRef.videoOnly
                    )

                    // Try preferred resolution first, otherwise default best
                    preferredResolution?.let { pref ->
                        StreamSelector.selectByResolution(
                            pref,
                            streamsRef.progressive,
                            streamsRef.videoOnly,
                            streamsRef.audio
                        )
                    } ?: StreamSelector.selectBest(
                        streamsRef.progressive,
                        streamsRef.videoOnly,
                        streamsRef.audio
                    )
                }

                if (streamResult == null) {
                    errorMessage = "Can't play this video"
                    return@launch
                }

                selectedResolution = streamResult.resolution
                val factory = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)
                if (streamResult.audioUrl != null) {
                    val videoSource = ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(MediaItem.fromUri(streamResult.videoUrl))
                    val audioSource = ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(MediaItem.fromUri(streamResult.audioUrl))
                    exoPlayer.setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else {
                    val source = ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(MediaItem.fromUri(streamResult.videoUrl))
                    exoPlayer.setMediaSource(source)
                }
                exoPlayer.prepare()
                AppLogger.success("Playing $vid at ${streamResult.resolution}")

            } catch (e: Exception) {
                AppLogger.error("Extraction failed: ${e.message}")
                errorMessage = "Can't play this video"
            }
        }
    }

    // Load playlist for auto-advance
    LaunchedEffect(playlistId) {
        val cached = ContentSourceRepository.getCachedVideos(db, playlistId)
        playlist = cached
        playlistRef.value = cached

        // Try to get source display name from DB
        try {
            val entity = withContext(Dispatchers.IO) {
                db.channelDao().getBySourceId(playlistId)
            }
            playlistTitle = entity?.displayName ?: playlistId
        } catch (_: Exception) {
            playlistTitle = playlistId
        }
    }

    // Collect commands from PlaybackCommandBus
    LaunchedEffect(Unit) {
        PlaybackCommandBus.commands.collect { command ->
            when (command) {
                PlaybackCommand.Stop -> {
                    onBack()
                }
                PlaybackCommand.SkipNext -> {
                    controller.advanceToNext?.invoke()
                }
                PlaybackCommand.SkipPrev -> {
                    controller.goToPrev?.invoke()
                }
                PlaybackCommand.TogglePause -> {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                        PlayEventRecorder.onPause()
                    } else {
                        exoPlayer.play()
                        PlayEventRecorder.onResume()
                    }
                }
                PlaybackCommand.SeekForward -> {
                    exoPlayer.seekTo(exoPlayer.currentPosition + 10_000)
                    seekMessage = "+10s"
                    controlsTrigger++
                }
                PlaybackCommand.SeekBack -> {
                    exoPlayer.seekTo(exoPlayer.currentPosition - 10_000)
                    seekMessage = "-10s"
                    controlsTrigger++
                }
            }
        }
    }

    // Player listener
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    controller.advanceToNext?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLogger.error("Player error: ${error.errorCodeName}")
                errorMessage = "Can't play this video"
                controller.skipAfterDelay?.invoke()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            val elapsed = ((System.currentTimeMillis() - playStartTime) / 1000).toInt()
            if (elapsed > 0) {
                val position = exoPlayer.currentPosition
                val duration = exoPlayer.duration
                val pct = if (duration > 0) ((position * 100) / duration).toInt() else 0
                PlayEventRecorder.endEvent(elapsed, pct)
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Periodic event update
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(1000)
            if (exoPlayer.isPlaying) {
                currentPosition = exoPlayer.currentPosition
                totalDuration = exoPlayer.duration
                
                val elapsed = ((System.currentTimeMillis() - playStartTime) / 1000).toInt()
                val pct = if (totalDuration > 0) ((currentPosition * 100) / totalDuration).toInt() else 0
                
                if (elapsed % 10 == 0) { // Still update backend every 10s
                    PlayEventRecorder.updateEvent(elapsed, pct)
                }
            }
        }
    }

    // Time limit check — separate from event update cadence
    var warningShown by remember { mutableStateOf(false) }
    var warningMinutes by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        // Pre-play check
        val initialStatus = ServiceLocator.timeLimitManager.canPlay()
        if (initialStatus is TimeLimitStatus.Blocked) {
            onLocked(initialStatus.reason.name.lowercase())
            return@LaunchedEffect
        }

        while (true) {
            delay(30_000)
            val status = ServiceLocator.timeLimitManager.canPlay()
            when (status) {
                is TimeLimitStatus.Blocked -> {
                    PlaybackCommandBus.send(PlaybackCommand.Stop)
                    onLocked(status.reason.name.lowercase())
                    return@LaunchedEffect
                }
                is TimeLimitStatus.Warning -> {
                    warningMinutes = status.minutesLeft
                    warningShown = true
                    // Auto-hide after 10 seconds
                    scope.launch {
                        delay(10_000)
                        warningShown = false
                    }
                }
                is TimeLimitStatus.Allowed -> {
                    warningShown = false
                }
            }
        }
    }

    // Start playback
    LaunchedEffect(videoId) {
        controller.extractAndPlay?.invoke(videoId)
    }

    // Request focus for D-pad
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Auto-hide UI controls after delay
    var showControls by remember { mutableStateOf(false) }
    LaunchedEffect(showControls, controlsTrigger, showResolutionPicker) {
        if (showControls && !showResolutionPicker) {
            delay(5000)
            showControls = false
        }
    }

    // Hide seek message after delay
    LaunchedEffect(seekMessage, controlsTrigger) {
        if (seekMessage != null) {
            delay(1500)
            seekMessage = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KidBackground)
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    controlsTrigger++
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_MENU -> {
                            if (availableResolutions.isNotEmpty()) {
                                if (showResolutionPicker) {
                                    if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                                        showResolutionPicker = false
                                        true
                                    } else false // Allow UP to navigate in list
                                } else {
                                    showResolutionPicker = true
                                    showControls = false
                                    true
                                }
                            } else false
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (showResolutionPicker) {
                                false // Allow DOWN to navigate in list
                            } else {
                                showControls = true
                                true
                            }
                        }
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            if (showResolutionPicker) {
                                showResolutionPicker = false
                                true
                            } else {
                                onBack()
                                true
                            }
                        }
                        else -> {
                            val command = DpadKeyHandler.mapKeyToCommand(event.nativeKeyEvent.keyCode)
                            if (command != null) {
                                PlaybackCommandBus.send(command)
                                showControls = true
                                true
                            } else false
                        }
                    }
                } else false
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    keepScreenOn = true
                    player = object : ForwardingPlayer(exoPlayer) {
                        override fun hasNextMediaItem(): Boolean =
                            indexRef.intValue < playlistRef.value.size - 1

                        override fun hasPreviousMediaItem(): Boolean = true

                        override fun seekToNextMediaItem() {
                            controller.advanceToNext?.invoke()
                        }

                        override fun seekToPreviousMediaItem() {
                            controller.goToPrev?.invoke()
                        }

                        override fun seekToNext() {
                            controller.advanceToNext?.invoke()
                        }

                        override fun seekToPrevious() {
                            controller.goToPrev?.invoke()
                        }
                    }
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Quality Icon Overlay (shown below progress bar on the right when controls are visible)
        if (showControls && availableResolutions.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp, end = 40.dp), // Positioned roughly near the end of progress bar
                contentAlignment = Alignment.BottomEnd
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(KidSurface.copy(alpha = 0.8f))
                        .clickable { showResolutionPicker = true }
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Quality",
                        tint = KidAccent,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = selectedResolution ?: "Auto",
                        style = MaterialTheme.typography.labelSmall,
                        color = KidText
                    )
                }
            }
        }

        // Resolution Picker Overlay
        if (showResolutionPicker) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { showResolutionPicker = false },
                contentAlignment = Alignment.CenterEnd
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(280.dp)
                        .background(KidSurface)
                        .padding(24.dp)
                        .clickable(enabled = false) {}
                ) {
                    Text(
                        text = "Quality",
                        style = MaterialTheme.typography.headlineSmall,
                        color = KidText,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    LazyColumn {
                        itemsIndexed(availableResolutions) { index, res ->
                            val isSelected = res == selectedResolution
                            var isFocused by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .then(
                                        if (index == 0 || res == selectedResolution)
                                            Modifier.focusRequester(resolutionFocusRequester)
                                        else Modifier
                                    )
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isFocused -> KidAccent
                                            isSelected -> KidAccent.copy(alpha = 0.2f)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .clickable {
                                        if (!isSelected) {
                                            val currentPos = exoPlayer.currentPosition
                                            val streamResult = StreamSelector.selectByResolution(
                                                res,
                                                streamsRef.progressive,
                                                streamsRef.videoOnly,
                                                streamsRef.audio
                                            )
                                            if (streamResult != null) {
                                                selectedResolution = res
                                                preferredResolution = res
                                                val factory = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)
                                                if (streamResult.audioUrl != null) {
                                                    val videoSource = ProgressiveMediaSource.Factory(factory)
                                                        .createMediaSource(MediaItem.fromUri(streamResult.videoUrl))
                                                    val audioSource = ProgressiveMediaSource.Factory(factory)
                                                        .createMediaSource(MediaItem.fromUri(streamResult.audioUrl))
                                                    exoPlayer.setMediaSource(MergingMediaSource(videoSource, audioSource))
                                                } else {
                                                    val source = ProgressiveMediaSource.Factory(factory)
                                                        .createMediaSource(MediaItem.fromUri(streamResult.videoUrl))
                                                    exoPlayer.setMediaSource(source)
                                                }
                                                exoPlayer.prepare()
                                                exoPlayer.seekTo(currentPos)
                                                exoPlayer.play()
                                                AppLogger.log("Switched to $res")
                                            }
                                        }
                                        showResolutionPicker = false
                                    }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = res,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = when {
                                        isFocused -> Color.White
                                        isSelected -> KidAccent
                                        else -> KidText
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (isFocused) Color.White else KidAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(KidBackground.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = StatusError,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.headlineMedium,
                        color = KidText,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { controller.extractAndPlay?.invoke(videoId) },
                        colors = ButtonDefaults.buttonColors(containerColor = KidAccent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(56.dp).width(200.dp)
                    ) {
                        Text("Retry", style = MaterialTheme.typography.titleMedium)
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Or press Back to exit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KidTextDim,
                    )
                }
            }
        }

        // Seek Visual Bubble
        if (seekMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = seekMessage!!,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
            }
        }

        // 'Up Next' Preview
        val nextVideo = playlist.getOrNull(currentVideoIndex + 1)
        val timeToEnd = totalDuration - currentPosition
        if (nextVideo != null && timeToEnd in 1..15000 && !showResolutionPicker && !showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 32.dp, end = 32.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Row(
                    modifier = Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(KidSurface.copy(alpha = 0.9f))
                        .border(BorderStroke(2.dp, KidAccent), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = nextVideo.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(80.dp, 45.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "UP NEXT",
                            style = MaterialTheme.typography.labelSmall,
                            color = KidAccent
                        )
                        Text(
                            text = nextVideo.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = KidText,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Time limit warning overlay
        if (warningShown) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(
                        StatusWarning.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "$warningMinutes minutes left!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = KidText,
                )
            }
        }
    }
}
