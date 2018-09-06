package com.intertrust.expressplay.modules.offlinevideo.helpers

import android.app.Activity
import android.content.Context
import android.os.Environment
import android.util.Log
import com.intertrust.expressplay.modules.offlinevideo.VideoFragment
import com.intertrust.wasabi.ErrorCodeException
import com.intertrust.wasabi.media.MediaDownload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*

/**
 * Created by Indri on 9/5/18.
 */

const val downloadDir = "rg_offline"

val downloadDirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/" + downloadDir

/**
 * Read a text file from the assets directory
 */
fun readTextFromAssets(tokenFileName: String, activity: Activity): String {
    val readBuffer = ByteArray(1024)
    val baos = ByteArrayOutputStream()
    val inputStream: InputStream?
    var bytesRead = 0

    try {
        inputStream = activity.assets.open(tokenFileName, Context.MODE_PRIVATE)

        while (bytesRead != -1) {
            bytesRead = inputStream.read(readBuffer)
            if (bytesRead == -1) break

            baos.write(readBuffer, 0, bytesRead)
        }
        baos.close()
        inputStream?.close()
    } catch (e: IOException) {
        e.printStackTrace()
        return ""
    }

    return String(baos.toByteArray())
}

/**
 * Helper method to avoid cluttering
 */

fun cleanup(mediaDownload: MediaDownload?,
            content: MediaDownload.DashContent,
            downloadDirPath: String) {

    //  cleanup previous downloads
    try {
        val mediaFile = File(downloadDirPath + "/" + content.media_file_name)
        if (mediaFile.exists()) {
            mediaFile.delete()
            Log.i("Cleanup", "deleted file: " + mediaFile.absolutePath)
        }

        val subtitleFile = File(downloadDirPath + "/" + content.subtitles_file_name)
        if (subtitleFile.exists()) {
            subtitleFile.delete()
            Log.i("Cleanup", "deleted file: " + subtitleFile.absolutePath)
        }

        val status = mediaDownload!!.queryStatus()

        val paths = status.path
        if (paths != null) {
            val pathList = Arrays.asList(*paths)
            for (item in pathList) {
                val contentStatus = mediaDownload.queryContentStatus(item)
                mediaDownload.cancelContent(item)
                Log.i("Cleanup", "canceling path $item")
            }
        }
    } catch (e: ErrorCodeException) {
        e.printStackTrace()
    }
}

/**
 * Determine if a resumable download is found
 */
fun isThereAnyResumableDownload(mediaDownload: MediaDownload): Boolean {
    try {
        val status = mediaDownload.queryStatus() //structure
        val state = status?.state //paused or running
        val path = status?.path
        path ?: return false

        for (pathItem in path) {
            val contentStatus = mediaDownload.queryContentStatus(pathItem)
            val contentState = contentStatus?.content_state
            val percentage = contentStatus?.downloaded_percentage
            Log.i(VideoFragment.TAG, "$pathItem in state $contentState at % $percentage")
            if (state == MediaDownload.State.PAUSED
                    && contentState == MediaDownload.ContentState.PENDING
                    && pathItem.contains(downloadDir)) {
                Log.i(VideoFragment.TAG, "resumable download found in dlDir1")
                return true

            }
        }
    } catch (e: ErrorCodeException) {
        e.printStackTrace()
    }

    return false
}

/**
 * Get local downloaded video file URI based on
 * @param contentStatus
 */
fun getDownloadedFileUri(contentStatus: MediaDownload.ContentStatus): String {
    if (contentStatus.content is MediaDownload.DashContent)
        return "file://" + contentStatus.path + "/" + (contentStatus.content as MediaDownload.DashContent).media_file_name

    return ""
}

/**
 * Get local downloaded subtitle file URI based on
 * @param contentStatus
 */
fun getDownloadedSubtitleUri(contentStatus: MediaDownload.ContentStatus): String {
    if (contentStatus.content is MediaDownload.DashContent)
        return "file://" + contentStatus.path + "/" + (contentStatus.content as MediaDownload.DashContent).subtitles_file_name

    return ""
}


fun isOfflineFileAvailable(filePath: String): Boolean {
    val file = File(filePath)
    return file.exists()
}