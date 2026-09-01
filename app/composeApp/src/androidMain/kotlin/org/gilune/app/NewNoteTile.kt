// The Quick Settings tile (ADR 0017 § 4).
//
// **New note only, and no microphone.** A QS tile is not a documented
// exemption for opening the mic from the background, so v1 ships a tile
// that does one thing an ordinary activity intent can do: open the app on
// its composer. A mic tile waits on a device test, and until then this
// class deliberately holds no recording code at all.

package org.gilune.app

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class NewNoteTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.gilune_tile_new_note)
            icon = Icon.createWithResource(this@NewNoteTile, R.drawable.ic_widget_new_note)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val pending = PendingIntent.getActivity(
            this,
            REQUEST,
            shellIntent(this, Routes.CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Android 14 removed the Intent overload's behaviour and requires
        // a PendingIntent; below that the PendingIntent form does not
        // exist. Both are the same tap.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(shellIntent(this, Routes.CAPTURE))
        }
    }

    private companion object {
        const val REQUEST = 30
    }
}
