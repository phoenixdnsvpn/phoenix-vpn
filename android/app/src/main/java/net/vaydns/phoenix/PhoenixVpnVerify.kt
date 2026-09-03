package net.vaydns.phoenix

import android.app.Application
import android.content.Context

class PhoenixVpnVerify : Application() {

    companion object {
        @Volatile
        private var done = false

        @JvmStatic
        external fun n0(context: Context)

        @JvmStatic
        fun bind(context: Context) {
            if (done) return
            synchronized(this) {
                if (done) return
                try {
                    System.loadLibrary("gojni")
                    PhoenixVpnVerify.n0(context.applicationContext)
                    done = true
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
    }
}