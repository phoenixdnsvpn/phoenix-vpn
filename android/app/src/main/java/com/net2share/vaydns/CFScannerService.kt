package com.net2share.vaydns

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import mobile.Mobile
import mobile.CFScannerCallback // <-- Required Gomobile Interface Import

class CFScannerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            "ACTION_START_SCAN" -> {
                val isDefault = intent.getBooleanExtra("IS_DEFAULT", false)
                val configIndex = intent.getLongExtra("CONFIG_INDEX", -1L)
                val scanCount = intent.getIntExtra("SCAN_COUNT", 512)

                Thread {
                    // Start the scanner and pass the callback interface for live updates
                    val finalResult = Mobile.runCloudflareScanner(isDefault, configIndex, scanCount.toLong(), object : CFScannerCallback {
                        override fun onUpdate(result: String) {
                            // Send intermediate updates to the UI WITHOUT killing the service
                            broadcastUpdate(result)
                        }
                    })

                    // Once all batches are completely finished, broadcast the final result and kill the service
                    broadcastResult(finalResult)
                }.start()
            }
            "ACTION_STOP_SCAN" -> {
                Mobile.stopCloudflareScanner()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Used for mid-scan updates (leaves the service running)
    private fun broadcastUpdate(rawResult: String) {
        val broadcastIntent = Intent("CF_SCANNER_RESULT").setPackage(packageName).apply {
            putExtra("RAW_RESULT", rawResult)
            putExtra("IS_FINISHED", false)
        }
        sendBroadcast(broadcastIntent)
    }

    // Used ONLY when the scan is 100% finished (cleans up the service)
    private fun broadcastResult(rawResult: String) {
        val broadcastIntent = Intent("CF_SCANNER_RESULT").setPackage(packageName).apply {
            putExtra("RAW_RESULT", rawResult)
            putExtra("IS_FINISHED", true)
        }
        sendBroadcast(broadcastIntent)

        Handler(Looper.getMainLooper()).post {
            stopSelf()
        }
    }
}