package com.intertrust.expressplay.helpers

import android.app.Activity
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Created by Indri on 9/5/18.
 */

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