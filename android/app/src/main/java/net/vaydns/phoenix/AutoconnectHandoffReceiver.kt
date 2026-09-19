package net.vaydns.phoenix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutoconnectHandoffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val index = intent.getLongExtra("CONFIG_INDEX", -1L)
        val proto = intent.getStringExtra("TUNNEL_PROTOCOL") ?: ""
        Log.i("PhoenixVPN", "Handoff receiver index=$index proto=$proto")
        if (index < 0) return

        val cb = MainActivity.onAutoconnectWinner

        context.getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE).edit()
            .putLong("handoff_config_index", index)
            .putString("handoff_tunnel_protocol", proto)
            .putBoolean("pending_autoconnect_handoff", cb == null)
            .commit()

        cb?.invoke(index)
    }
}