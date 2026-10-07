package net.vaydns.phoenix

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ProtocolSelectorBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.bottom_sheet_protocols, container, false)

        view.findViewById<View>(R.id.btnVayDns)?.setOnClickListener {
            launchConfigEditor("VayDNS")
        }

        view.findViewById<View>(R.id.btnMasterDns)?.setOnClickListener {
            launchConfigEditor("MasterDNS")
        }

        view.findViewById<View>(R.id.btnCottenDns)?.setOnClickListener {
            launchConfigEditor("CottenDNS")
        }

        view.findViewById<View>(R.id.btnSlipstream)?.setOnClickListener {
            launchConfigEditor("Slipstream")
        }

        // NEW: StormDNS routing
        view.findViewById<View>(R.id.btnStormDns)?.setOnClickListener {
            launchConfigEditor("StormDNS")
        }

        view.findViewById<View>(R.id.btnHysteria)?.setOnClickListener {
            val intent = Intent(requireContext(), HysteriaConfigEditorActivity::class.java).apply {
                putExtra("IS_NEW_CONFIG", true)
            }
            startActivity(intent)
            dismiss()
        }

        view.findViewById<View>(R.id.btnVless)?.setOnClickListener {
            val intent = Intent(requireContext(), VlessConfigEditorActivity::class.java).apply {
                putExtra("IS_NEW_CONFIG", true)
            }
            startActivity(intent)
            dismiss()
        }

        view.findViewById<View>(R.id.btnTrojan)?.setOnClickListener {
            val intent = Intent(requireContext(), TrojanConfigEditorActivity::class.java).apply {
                putExtra("IS_NEW_CONFIG", true)
            }
            startActivity(intent)
            dismiss()
        }

        view.findViewById<View>(R.id.btnShadowsocks)?.setOnClickListener {
            val intent = Intent(requireContext(), ShadowsocksConfigEditorActivity::class.java).apply {
                putExtra("IS_NEW_CONFIG", true)
            }
            startActivity(intent)
            dismiss()
        }

        view.findViewById<View>(R.id.btnAmnezia)?.setOnClickListener {
            val intent = Intent(requireContext(), AmneziaWgConfigEditorActivity::class.java).apply {
                putExtra("IS_NEW_CONFIG", true)
            }
            startActivity(intent)
            dismiss()
        }

        return view
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }

        return dialog
    }

    private fun launchConfigEditor(protocol: String) {
        // UPDATED: Route to CustomDnsConfigEditorActivity instead of the deprecated ConfigEditorActivity
        val intent = Intent(requireContext(), CustomDnsConfigEditorActivity::class.java).apply {
            putExtra("PROTOCOL_TYPE", protocol)
            putExtra("IS_NEW_CONFIG", true)
        }
        startActivity(intent)
        dismiss()
    }
}