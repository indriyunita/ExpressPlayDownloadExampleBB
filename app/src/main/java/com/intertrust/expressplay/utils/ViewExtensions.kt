@file:JvmName("ViewUtils")
package com.intertrust.expressplay.utils

import android.app.Activity
import android.support.v4.app.Fragment
import android.view.View
import android.view.ViewGroup

/**
 * Created by Indri on 16/10/2017.
 */

inline fun <reified T : View> View.find(id: Int): T = findViewById(id) as T

inline fun <reified T : View> Activity.find(id: Int): T = findViewById(id) as T

inline fun <reified T : View> Fragment.find(id: Int): T = view?.findViewById(id) as T

fun View.setMargins(left: Int, top: Int, right: Int, bottom: Int) {
    if (this.layoutParams is ViewGroup.MarginLayoutParams) {
        val p = this.layoutParams as ViewGroup.MarginLayoutParams
        p.setMargins(left, top, right, bottom)
        this.requestLayout()
    }
}

fun View.setRightMarginOnly(right: Int)  = this.setMargins(0, 0, right, 0)

fun View.setLeftMarginOnly(left: Int) = this.setMargins(left, 0, 0, 0)

fun View.setTopMarginOnly(top: Int) = this.setMargins(0, top, 0, 0)

fun View.setBottomMarginOnly(bottom: Int) = this.setMargins(0, 0, 0, bottom)
