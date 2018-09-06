package com.intertrust.expressplay.modules.offlinevideo.helpers

/**
 * This enum simply maps the media types to the mimetypes required for the playlist proxy
 */
enum class ContentTypes(val mediaSourceParamsContentType: String) {
    DASH("application/dash+xml"),
    HLS("application/vnd.apple.mpegurl"),
    PDCF("video/mp4"),
    M4F("video/mp4"),
    DCF("application/vnd.oma.drm.dcf"),
    BBTS("video/mp2t");
}