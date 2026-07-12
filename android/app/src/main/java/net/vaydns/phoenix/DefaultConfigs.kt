package net.vaydns.phoenix

object DefaultConfigs {

    init {
        System.loadLibrary("default_configs")
    }

    external fun loadConfigs(assetManager: android.content.res.AssetManager)

    external fun getConfigCount(): Int
    external fun getConfigName(index: Int): String
    external fun getConfigDomain(index: Int): String
    external fun getConfigPubkey(index: Int): String
}