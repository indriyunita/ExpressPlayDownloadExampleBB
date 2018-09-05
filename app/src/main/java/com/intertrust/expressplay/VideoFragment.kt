package com.intertrust.expressplay

import android.app.AlertDialog
import android.app.Fragment
import android.app.ProgressDialog
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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

import java.io.File
import java.util.Arrays
import java.util.EnumSet

import android.content.Context.MODE_PRIVATE
import android.text.TextUtils
import com.intertrust.expressplay.helpers.readTextFromAssets
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

    private lateinit var rootView: View
    private val simpleExoPlayerView by lazy {
        rootView.find<SimpleExoPlayerView>(R.id.videoView)
    }

    private val playerProxy: PlaylistProxy by lazy {
        val flags = EnumSet.noneOf(PlaylistProxy.Flags::class.java)
        PlaylistProxy(flags, this, Handler())
    }

    private var mediaDownload: MediaDownload? = null
    private var isPlaying: Boolean = false
    private var player: SimpleExoPlayer? = null

    private val downloadDir1 = "dlDir1"
    private val dlDirPath1 = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/" + downloadDir1
    private var resumableDownloadFound = false
    /**
     * Acquire a DASH On-Demand media stream URL encrypted with the key delivered in
     * the above license.
     * For instance: http://content-access.intertrust-dev.com/content/onDemandprofile/Frozen-OnDemand/stream.mpd
     */
    private val sampleDashUrl = "http://content-access.intertrust-dev.com/content/onDemandprofile/Frozen-OnDemand/stream.mpd"
    private val content = getDashContent()
    private val progressDialog: ProgressDialog by lazy { getProgressIndicator() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {

        rootView = inflater.inflate(R.layout.fragment_marlin_broadband_example, container, false)

        initExoPlayer()
        initAndPersonalizeWasabi()
        acquireLicense()
        initPlayerProxy()
        createMediaDownload()
        checkResumableDownload()
        setMediaDownloadConstraints()

        /**
         * If resumable download found, and not dual download case,
         *  offer choice of resuming or cleaning up
         */
        if (resumableDownloadFound) {
            showResumeOrCleanupPromptDialog()
        }

        doDownload()

        return rootView
    }

    private fun initExoPlayer() {
        val bandwidthMeter = DefaultBandwidthMeter()
        val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory(bandwidthMeter)
        val trackSelector = DefaultTrackSelector(videoTrackSelectionFactory)
        val loadControl = DefaultLoadControl()


        player = ExoPlayerFactory.newSimpleInstance(activity, trackSelector, loadControl)

        simpleExoPlayerView.useController = true
        simpleExoPlayerView.requestFocus()
        simpleExoPlayerView.player = player
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
            Runtime.initialize(activity.getDir("wasabi", MODE_PRIVATE)
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
            Log.e(TAG, "runtime initialization or personalization npe: " + e.localizedMessage)
        } catch (e: ErrorCodeException) {
            Log.e(TAG, "runtime initialization or personalization error: " + e.localizedMessage)
        }
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
         * Create  MediaDownload singleton
         */

        try {
            mediaDownload = MediaDownload()
        } catch (e: ErrorCodeException) {
            e.printStackTrace()
        }
    }

    private fun checkResumableDownload() {
        /**
         * Determine if a resumable download is found
         */

        try {
            val status = mediaDownload!!.queryStatus() //structure
            val state = status.state //paused or running
            val path = status.path
            for (pathItem in path) {
                val contentStatus = mediaDownload!!.queryContentStatus(pathItem)
                val contentState = contentStatus.content_state
                val percentage = contentStatus.downloaded_percentage
                Log.i(TAG, "$pathItem in state $contentState at % $percentage")
                if (state == MediaDownload.State.PAUSED
                        && contentState == MediaDownload.ContentState.PENDING
                        && pathItem.contains(downloadDir1)) {
                    resumableDownloadFound = true
                    Log.i(TAG, "resumable download found in dlDir1")
                    break
                }
            }
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
            mediaDownload?.setConstraints(constraints)
        } catch (e: ErrorCodeException) {
            e.printStackTrace()
        }
    }

    private fun getDashContent(): MediaDownload.DashContent = MediaDownload.DashContent().apply {
        //Specify the tracks/subtitles to download - note these are specific to the example
        //DASH URL above
        val tracks = arrayOf("video-avc1", "audio-und-mp4a", "subtitles/fr")
        this.track = tracks
        this.subtitles_file_name = "mydownload-subtitles.vtt"
        this.media_file_name = "mydownload-media.m4f"
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
        builder.setPositiveButton("OK") { _, i ->
            try {
                mediaDownload?.resume()
                progressDialog.setMessage("Resuming Download.... to $dlDirPath1")
                progressDialog.show()
            } catch (e: ErrorCodeException) {
                e.printStackTrace()
            }
        }
        builder.setNegativeButton("Cancel") { _, i ->
            //                      cleanup previous downloads
            cleanup(mediaDownload, content, dlDirPath1, dlDirPath1)
            Toast.makeText(activity, "All Downloads Canceled - Please Kill and Restart App", Toast.LENGTH_LONG).show()
        }
        builder.show()
    }

    private fun doDownload() {
        /**
         * Define the single listener for the MediaDownload object
         * The listener will start the playback using the Playlist Proxy once the
         * first of the download is complete
         */

        try {
            mediaDownload?.setListener(object : MediaDownload.Listener {

                override fun state(state: MediaDownload.State) {
                    Log.i(TAG, "Received State Update: $state")
                }

                override fun progress(contentStatus: MediaDownload.ContentStatus) {
                    val handler = Handler(Looper.getMainLooper())
                    // playback of downloaded content starts at this percentage
                    val percentageStartPlay = 100

                    if (contentStatus.content_state == MediaDownload.ContentState.FAILING) {
                        Log.i(TAG, "Media Download Failing on: " + contentStatus.path)
                        handler.post { Toast.makeText(activity, "Media Download Failing: " + contentStatus.path, Toast.LENGTH_SHORT).show() }
                    }

                    if (contentStatus.path.contains(downloadDir1)) {
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
                            && contentStatus.path.contains(downloadDir1)
                            && !isPlaying) {
                        //playback the 1st of the content downloads
                        handler.post {
                            val dlFileUrl = "file://" + contentStatus.path + "/" + content.media_file_name
                            val subtitleUrl = "file://" + contentStatus.path + "/" + content.subtitles_file_name
                            val contentType = ContentTypes.M4F
                            val params = PlaylistProxy.MediaSourceParams()
                            params.sourceContentType = contentType.mediaSourceParamsContentType

                            if (subtitleUrl != null) {
                                params.subtitleUrl = subtitleUrl
                                params.subtitleLang = "default"
                                params.subtitleName = "default subtitle"
                            }

                            val contentTypeValue = contentType.toString()
                            val mediaSourceType = PlaylistProxy.MediaSourceType.valueOf(
                                    if (contentTypeValue == "HLS" || contentTypeValue == "DASH") contentTypeValue else "SINGLE_FILE")


                            val bandwidthMeterA = DefaultBandwidthMeter()
                            val dataSourceFactory = DefaultDataSourceFactory(activity, Util.getUserAgent(activity, "exoplayer2example"), bandwidthMeterA)


                            try {
                                val proxyUri = playerProxy.makeUrl(dlFileUrl, mediaSourceType, params)
                                val mp4VideoUri = Uri.parse(proxyUri)
                                val videoSource = HlsMediaSource(mp4VideoUri, dataSourceFactory, 1, null, null)
                                val loopingSource = LoopingMediaSource(videoSource)

                                player?.prepare(loopingSource)

                                player?.addListener(object : ExoPlayer.EventListener {
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
                                        player?.stop()
                                        player?.prepare(loopingSource)
                                        player?.playWhenReady = true
                                    }

                                    override fun onPositionDiscontinuity() {
                                        Log.v(TAG, "Listener-onPositionDiscontinuity...")
                                    }

                                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                                        Log.v(TAG, "Listener-onPlaybackParametersChanged...")
                                    }
                                })

                                player?.playWhenReady = true //run file/link when ready to play.
                                progressDialog.cancel()
                                isPlaying = true
                            } catch (e: Exception) {
                                Log.e(TAG, "playback error: " + e.localizedMessage)
                                e.printStackTrace()
                            }
                        }
                    }
                }
            })

            /*
             * start the downloads if no resumable download was found or it is a dual download case
             */
            if (!resumableDownloadFound) {
                //cleanup just in case...
                cleanup(mediaDownload, content, dlDirPath1, dlDirPath1)

                //start the 1st media download with progress bar
                mediaDownload?.resume()
                mediaDownload?.addContent(dlDirPath1, content)
                progressDialog.setMessage("Downloading.... to $dlDirPath1")
                progressDialog.show()
            }
        } catch (e: ErrorCodeException) {
            e.printStackTrace()
        }
    }


    /**************************************
     * Helper methods to avoid cluttering *
     */

    fun cleanup(mediaDownload: MediaDownload?, content: MediaDownload.DashContent,
                dlDirPath1: String, dlDirPath2: String) {
        //  cleanup previous downloads
        val pathItems = arrayOf(dlDirPath1, dlDirPath2)
        for (pathItem in pathItems) {
            try {
                val mediaFile = File(pathItem + "/" + content.media_file_name)
                if (mediaFile.exists()) {
                    mediaFile.delete()
                    Log.i(TAG, "deleted file: " + mediaFile.absolutePath)
                }
                val subtitleFile = File(pathItem + "/" + content.subtitles_file_name)
                if (subtitleFile.exists()) {
                    subtitleFile.delete()
                    Log.i(TAG, "deleted file: " + subtitleFile.absolutePath)
                }
                val status = mediaDownload!!.queryStatus()
                val paths = status.path
                if (paths != null) {
                    val pathList = Arrays.asList(*paths)
                    for (item in pathList) {
                        val contentStatus = mediaDownload.queryContentStatus(item)
                        mediaDownload.cancelContent(item)
                        Log.i(TAG, "canceling path $item")
                    }
                }
            } catch (e: ErrorCodeException) {
                e.printStackTrace()
            }

        }
    }


    override fun onErrorNotification(errorCode: Int, errorString: String) {
        Log.e(TAG, "PlaylistProxy Event: Error Notification, error code = " +
                Integer.toString(errorCode) + ", error string = " +
                errorString)
    }

    companion object {
        internal val TAG = "SampleBBPlayer"
    }
}
