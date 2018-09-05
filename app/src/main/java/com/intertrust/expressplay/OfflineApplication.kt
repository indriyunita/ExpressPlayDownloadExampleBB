package com.intertrust.expressplay

import android.app.Application
import android.content.Context
import android.util.Log
import com.intertrust.wasabi.ErrorCodeException
import com.intertrust.wasabi.Runtime

/**
 * Created by Indri on 9/5/18.
 */

class OfflineApplication: Application() {

    init {
//        initAndPersonalizeWasabi()
    }

//    private fun initAndPersonalizeWasabi() {
//        try {
//            /**
//             * Initialize the Wasabi Runtime (necessary only once for each
//             * instantiation of the application)
//             *
//             * ** Note: Set Runtime Properties as needed for your
//             * environment
//             */
//            Runtime.setProperty(Runtime.Property.ROOTED_OK, true)
//            Runtime.initialize(getDir("wasabi", Context.MODE_PRIVATE)
//                    .absolutePath)
//            /**
//             * Personalize the application (acquire DRM keys). This is only
//             * necessary once each time the application is freshly installed
//             *
//             * ** Note: personalize() is a blocking call and may take long
//             * enough to complete to trigger ANR (Application Not
//             * Responding) errors. In a production application this should
//             * be called in a background thread.
//             */
//            if (!Runtime.isPersonalized())
//                Runtime.personalize()
//
//        } catch (e: NullPointerException) {
//            Log.e("OfflineApplication", "runtime initialization or personalization npe: " + e.localizedMessage)
//        } catch (e: ErrorCodeException) {
//            Log.e("OfflineApplication", "runtime initialization or personalization error: " + e.localizedMessage)
//        }
//    }
}