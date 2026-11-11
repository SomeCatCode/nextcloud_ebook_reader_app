package com.somecatcode.ebookreader.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.somecatcode.ebookreader.R

/** Links to the app in Google Play. The package name is fixed, so debug builds link to the release app too. */
object AppLinks {
    const val PACKAGE = "com.somecatcode.ebookreader"
    const val PLAY_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    /** Opens the app page in the Play Store app, or in the browser when the Play Store is missing. */
    fun openStorePage(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
            .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // no browser either: nothing to open
            }
        }
    }

    /** The share sheet with a short text and the Play Store link. */
    fun shareIntent(context: Context): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_text, PLAY_URL))
        return Intent.createChooser(send, context.getString(R.string.settings_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun share(context: Context) {
        try {
            context.startActivity(shareIntent(context))
        } catch (_: ActivityNotFoundException) {
            // no app can receive text
        }
    }
}
