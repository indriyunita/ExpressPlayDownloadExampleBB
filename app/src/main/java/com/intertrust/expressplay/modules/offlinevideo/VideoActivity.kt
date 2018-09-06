package com.intertrust.expressplay.modules.offlinevideo

import android.app.Activity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.intertrust.expressplay.R


class VideoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_marlin_broadband_example)

        if (savedInstanceState == null) {
            fragmentManager.beginTransaction()
                    .add(R.id.container, VideoFragment()).commit()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {

        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.marlin_broadband_example, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        return if (id == R.id.action_settings) {
            true
        } else super.onOptionsItemSelected(item)
    }


}

