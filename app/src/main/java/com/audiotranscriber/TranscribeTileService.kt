package com.audiotranscriber

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class TranscribeTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onTileAdded() {
        updateTile()
    }

    override fun onClick() {
        if (AudioCaptureService.isRecording) {
            sendAction(AudioCaptureService.ACTION_STOP_CAPTURE)
        } else {
            sendAction(AudioCaptureService.ACTION_START_CAPTURE)
        }
        updateTile()
    }

    private fun sendAction(action: String) {
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (_: Throwable) {}
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when {
            AudioCaptureService.isRecording -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Recording…"
            }
            AudioCaptureService.isRunning -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Transcribe"
            }
            else -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "Transcriber off"
            }
        }
        try { tile.updateTile() } catch (_: Throwable) {}
    }
}
