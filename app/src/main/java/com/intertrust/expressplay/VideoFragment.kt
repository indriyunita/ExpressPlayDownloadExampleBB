package com.intertrust.expressplay

import android.app.AlertDialog
import android.app.Fragment
import android.app.ProgressDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast

import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.source.LoopingMediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.ui.SimpleExoPlayerView
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import com.intertrust.wasabi.ErrorCodeException
import com.intertrust.wasabi.Runtime
import com.intertrust.wasabi.media.MediaDownload
import com.intertrust.wasabi.media.PlaylistProxy
import com.intertrust.wasabi.media.PlaylistProxyListener

import java.util.EnumSet

import android.text.TextUtils
import com.intertrust.expressplay.helpers.*
import com.intertrust.expressplay.utils.find


/**
 * This example illustrates:
 * - initialization of the Wasabi Runtime
 * - acquisition of a Marlin Broadband (BB) license for a DASH media manifest
 * - download of the  DASH media to local fragmented MO4 files using MediaDownload API
 * - playback of the downloaded media once the MediaDownload listener is informed that
 *   the download is complete.  Playback uses PlaylistProxy and the previously
 *   acquired Marlin BB license.
 *
 *   Upon restart, the app checks whether a download is PENDING in the main download dir.
 *   This would be the case if the app were killed during a download. The MediaDownload
 *   will then be PAUSED and the content PENDING.  If that is the case during app startup,
 *   it offers the choice to resume the pending download or to cancel it.  If canceling,
 *   the app will clean the downloaded media and the MediaDownload object.
 *
 *   In all other cases of COMPLETE or FAILING downloads, the app will not resume.
 *
 *   The app operates in 2 modes - as above or in a "parallel" download mode, where 2 downloads are
 *   started with some time differential.  In that mode the app cleans up at startup and does not offer
 *   the option to resume a pending download.
 *
 *
 */

class VideoFragment : Fragment(), PlaylistProxyListener {

    // View related
    private lateinit var rootView: View
    private val simpleExoPlayerView by lazy {
        rootView.find<SimpleExoPlayerView>(R.id.videoView)
    }
    private val progressDialog: ProgressDialog by lazy { getProgressIndicator() }
    private val handler = Handler(Looper.getMainLooper())

    // DRM related
    private val playerProxy: PlaylistProxy by lazy {
        val flags = EnumSet.noneOf(PlaylistProxy.Flags::class.java)
        PlaylistProxy(flags, this, handler)
    }
    private lateinit var mediaDownload: MediaDownload
    private var resumableDownloadFound = false
    private val content = getDashContent()

    // Player related
    private var isPlaying: Boolean = false
    private val player: SimpleExoPlayer by lazy {
        val bandwidthMeter = DefaultBandwidthMeter()
        val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory(bandwidthMeter)
        val trackSelector = DefaultTrackSelector(videoTrackSelectionFactory)
        val loadControl = DefaultLoadControl()
        ExoPlayerFactory.newSimpleInstance(activity, trackSelector, loadControl)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        rootView = inflater.inflate(R.layout.fragment_marlin_broadband_example, container, false)

        initAndPersonalizeWasabi()
        initExoPlayer()
        acquireLicense()
        initPlayerProxy()
        createMediaDownload()
        setMediaDownloadConstraints()

        resumableDownloadFound = isThereAnyResumableDownload(mediaDownload)
        if (resumableDownloadFound)
            showResumeOrCleanupPromptDialog()

        if (isOfflineFileAvailable("$downloadDirPath/$FILENAME"))
            playOffline()
        else
            doDownload()

        return rootView
    }

    private fun initAndPersonalizeWasabi() {
        try {
            /**
             * Initialize the Wasabi Runtime (necessary only once for each
             * instantiation of the application)
             *
             * ** Note: Set Runtime Properties as needed for your
             * environment
             */
            Runtime.setProperty(Runtime.Property.ROOTED_OK, true)
            Runtime.initialize(activity.getDir("wasabi", Context.MODE_PRIVATE)
                    .absolutePath)
            /**
             * Personalize the application (acquire DRM keys). This is only
             * necessary once each time the application is freshly installed
             *
             * ** Note: personalize() is a blocking call and may take long
             * enough to complete to trigger ANR (Application Not
             * Responding) errors. In a production application this should
             * be called in a background thread.
             */
            if (!Runtime.isPersonalized())
                Runtime.personalize()

        } catch (e: NullPointerException) {
            Log.e("OfflineApplication", "runtime initialization or personalization npe: " + e.localizedMessage)
        } catch (e: ErrorCodeException) {
            Log.e("OfflineApplication", "runtime initialization or personalization error: " + e.localizedMessage)
        }
    }

    private fun initExoPlayer() {
        simpleExoPlayerView.useController = true
        simpleExoPlayerView.requestFocus()
        simpleExoPlayerView.player = player
    }

    private fun acquireLicense() {
        /**
         * Acquire a Marlin Broadband License. The license is acquired using
         * a License Acquisition token. Such tokens for sample content can
         * be obtained from http://content.intertrust.com/express/ and in
         * this example are stored in the Android project /assets directory
         * using the filename "license-token.xml".
         *
         * For instance, you can download such a token from
         * http://content-access.intertrust-dev.com/Dash_OnDemand_Subtitle/bb, and save it
         * to the assets directory as license-token.xml"
         *
         * *** Note: processServiceToken() is a blocking call and may take
         * long enough to complete to trigger ANR (Application Not
         * Responding) errors. In a production application this should be
         * called in a background thread.
         */
        val licenseAcquisitionToken = readTextFromAssets("license-token.xml", activity)
        if (TextUtils.isEmpty(licenseAcquisitionToken)) {
            Log.e(TAG, "Could not find action token in the assets directory local - exiting")
        }
        val start = System.currentTimeMillis()
        try {
            Runtime.processServiceToken(licenseAcquisitionToken)
            Log.i(TAG, "License successfully acquired in (ms): " + (System.currentTimeMillis() - start))
        } catch (e1: ErrorCodeException) {
            Log.e(TAG, "Could not acquire the license from the license acquisition token remote - exiting " + e1.localizedMessage)
        }
    }

    private fun initPlayerProxy() {
        /**
         * Init a playlist proxy for later playback and start it
         */
        try {
            playerProxy.start()
        } catch (e: ErrorCodeException) {
            Log.e(TAG, "playlist proxy error: " + e.localizedMessage)
        }
    }

    private fun createMediaDownload() {
        /**
         * Create  MediaDownload instance
         */

        try {
            mediaDownload = MediaDownload()
        } catch (e: ErrorCodeException) {
            e.printStackTrace()
        }
    }

    private fun setMediaDownloadConstraints() {
        /**
         * Setup a simple MediaDownload object
         * we'll use one single content item for both downloads
         */
        val constraints = MediaDownload.Constraints()
        // set some download parameters
        constraints.max_bandwidth_bps = 20 * 1024 * 1024
        //use 2 connections for parallel download
        constraints.max_connections = 2
        try {
            mediaDownload.setConstraints(constraints)
        } catch (e: ErrorCodeException) {
            e.printStackTrace()
        }
    }

    private fun getDashContent(): MediaDownload.DashContent = MediaDownload.DashContent().apply {
        //Specify the tracks/subtitles to download - note these are specific to the example
        //DASH URL above
        val tracks = arrayOf("video-avc1", "audio-und-mp4a", "subtitles/fr")
        this.track = tracks
        this.media_file_name = FILENAME
        this.subtitles_file_name = SUBTITLE
        this.url = sampleDashUrl
        this.type = MediaDownload.SourceType.DASH
    }

    private fun getProgressIndicator(): ProgressDialog = ProgressDialog(this.activity).apply {
        this.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        this.isIndeterminate = false
        this.setCancelable(true)
    }

    private fun showResumeOrCleanupPromptDialog() {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("Resume Download ?")
        val dialogMessage = "A previously started Download to dlDir1 (possibly others) is Pending. " + "Click OK to Resume Any Pending Downloads, or Cancel to Clear all Downloads)"
        builder.setMessage(dialogMessage)
        builder.setPositiveButton("OK") { _, _ ->
            try {
                mediaDownload.resume()
                progressDialog.setMessage("Resuming Download.... to $downloadDirPath")
                progressDialog.show()
            } catch (e: ErrorCodeException) {
                e.printStackTrace()
            }
        }
        builder.setNegativeButton("Cancel") { _, _ ->
            //                      cleanup previous downloads
            cleanup(mediaDownload, content, downloadDirPath)
            Toast.makeText(activity, "All Downloads Canceled - Please Kill and Restart App", Toast.LENGTH_LONG).show()
        }
        builder.show()
    }

    private fun playOffline() {
        handler.post {
            playVideo(getOfflineVideoSource())
        }
    }

    private fun doDownload() {
        /**
         * Define the single listener for the MediaDownload object
         * The listener will start the playback using the Playlist Proxy once the
         * first of the download is complete
         */

        try {
            mediaDownload.setListener(object : MediaDownload.Listener {

                override fun state(state: MediaDownload.State) {
                    Log.i(TAG, "Received State Update: $state")
                }

                override fun progress(contentStatus: MediaDownload.ContentStatus) {
                    // playback of downloaded content starts at this percentage
                    val percentageStartPlay = 100

                    if (contentStatus.content_state == MediaDownload.ContentState.FAILING) {
                        Log.i(TAG, "Media Download Failing on: " + contentStatus.path)
                        handler.post { Toast.makeText(activity, "Media Download Failing: " + contentStatus.path, Toast.LENGTH_SHORT).show() }
                    }

                    if (contentStatus.path.contains(downloadDir)) {
                        val progress = contentStatus.downloaded_percentage
                        progressDialog.progress = progress
                    }

                    if (contentStatus.content_state == MediaDownload.ContentState.COMPLETED) {
                        Log.i(TAG, "Media Download Complete on: " + contentStatus.path)
                        handler.post {
                            progressDialog.cancel()
                            Toast.makeText(activity, "Media Download Complete on: " + contentStatus.path, Toast.LENGTH_LONG).show()
                        }
                    }

                    if (contentStatus.downloaded_percentage == percentageStartPlay
                            && contentStatus.path.contains(downloadDir)
                            && !isPlaying) {

                        val videoSource = getOnlineVideoSource(contentStatus)

                        //playback the 1st of the content downloads
                        handler.post {
                            playVideo(videoSource)
                        }
                    }
                }
            })

            /*
             * start the downloads if no resumable download was found or it is a dual download case
             */
            if (!resumableDownloadFound) {
                startDownload(content, downloadDirPath)
            }
        } catch (e: ErrorCodeException) {
            e.printStackTrace()
        }
    }

    private fun startDownload(content: MediaDownload.DashContent, downloadDirPath: String) {
        mediaDownload.resume()
        mediaDownload.addContent(downloadDirPath, content)

        progressDialog.setMessage("Downloading.... to $downloadDirPath")
        progressDialog.show()
    }

    private fun getOnlineVideoSource(contentStatus: MediaDownload.ContentStatus): LoopingMediaSource {
        val downloadedFileUri = getDownloadedFileUri(contentStatus)
        val subtitleUri = getDownloadedSubtitleUri(contentStatus)

        val contentType = ContentTypes.M4F
        val contentTypeValue = contentType.toString()
        val bandwidthMeterA = DefaultBandwidthMeter()
        val dataSourceFactory = DefaultDataSourceFactory(activity, Util.getUserAgent(activity, "exoplayer2example"), bandwidthMeterA)

        val mediaSourceType = PlaylistProxy.MediaSourceType.valueOf(if (contentTypeValue == "HLS" || contentTypeValue == "DASH") contentTypeValue else "SINGLE_FILE")
        val mediaSourceParams = PlaylistProxy.MediaSourceParams().apply {
            this.sourceContentType = contentType.mediaSourceParamsContentType
            this.subtitleUrl = subtitleUri
            this.subtitleLang = "default"
            this.subtitleName = "default subtitle"
        }

        val proxyUri = playerProxy.makeUrl(downloadedFileUri, mediaSourceType, mediaSourceParams)
        val mp4VideoUri = Uri.parse(proxyUri)
        val videoSource = HlsMediaSource(mp4VideoUri, dataSourceFactory, 1, null, null)
        return LoopingMediaSource(videoSource)
    }

    private fun getOfflineVideoSource(): LoopingMediaSource {
        val contentType = ContentTypes.M4F
        val contentTypeValue = contentType.toString()
        val bandwidthMeterA = DefaultBandwidthMeter()
        val dataSourceFactory = DefaultDataSourceFactory(activity, Util.getUserAgent(activity, "exoplayer2example"), bandwidthMeterA)

        val mediaSourceType = PlaylistProxy.MediaSourceType.valueOf(if (contentTypeValue == "HLS" || contentTypeValue == "DASH") contentTypeValue else "SINGLE_FILE")
        val mediaSourceParams = PlaylistProxy.MediaSourceParams().apply {
            this.sourceContentType = contentType.mediaSourceParamsContentType
            this.subtitleLang = "default"
            this.subtitleName = "default subtitle"
        }

        val offlineUri = "$downloadDirPath/$FILENAME"
        val proxyUri = playerProxy.makeUrl(offlineUri, mediaSourceType, mediaSourceParams)
        val mp4VideoUri = Uri.parse(proxyUri)
        val videoSource = HlsMediaSource(mp4VideoUri, dataSourceFactory, 1, null, null)
        return LoopingMediaSource(videoSource)
    }

    private fun playVideo(loopingSource: LoopingMediaSource) {
        try {
            player.prepare(loopingSource)
            player.addListener(object : ExoPlayer.EventListener {
                override fun onTimelineChanged(timeline: Timeline, manifest: Any) {
                    Log.v(TAG, "Listener-onTimelineChanged...")
                }

                override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
                    Log.v(TAG, "Listener-onTracksChanged...")
                }

                override fun onLoadingChanged(isLoading: Boolean) {
                    Log.v(TAG, "Listener-onLoadingChanged...isLoading:$isLoading")
                }

                override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                    Log.v(TAG, "Listener-onPlayerStateChanged...$playbackState")
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    Log.v(TAG, "Listener-onRepeatModeChanged...")
                }

                override fun onPlayerError(error: ExoPlaybackException) {
                    Log.v(TAG, "Listener-onPlayerError...")
                    player.stop()
                    player.prepare(loopingSource)
                    player.playWhenReady = true
                }

                override fun onPositionDiscontinuity() {
                    Log.v(TAG, "Listener-onPositionDiscontinuity...")
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    Log.v(TAG, "Listener-onPlaybackParametersChanged...")
                }
            })

            player.playWhenReady = true //run file/link when ready to play.
            progressDialog.cancel()
            isPlaying = true
        } catch (e: Exception) {
            Log.e(TAG, "playback error: " + e.localizedMessage)
            e.printStackTrace()
        }
    }

    override fun onErrorNotification(errorCode: Int, errorString: String) {
        Log.e(TAG, "PlaylistProxy Event: Error Notification, error code = " +
                Integer.toString(errorCode) + ", error string = " +
                errorString)
    }

    companion object {
        const val TAG = "SampleBBPlayer"
        const val sampleDashUrl = "http://content-access.intertrust-dev.com/content/onDemandprofile/Frozen-OnDemand/stream.mpd"
        const val FILENAME = "mydownload-media.m4f"
        const val SUBTITLE = "mydownload-subtitles.vtt"
    }
}
