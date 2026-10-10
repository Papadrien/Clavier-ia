package fr.junade.taipo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Ouvre une page web dans le navigateur ; message court s'il n'y en a aucun (story 8.11, liens de licence). */
fun Context.openWebLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.licenses_no_browser, Toast.LENGTH_LONG).show()
    }
}
