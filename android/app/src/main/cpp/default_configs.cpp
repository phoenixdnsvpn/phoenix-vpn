#include <jni.h>
#include <string>
#include <vector>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#define LOG_TAG "DefaultConfigs"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Structure to hold each config
struct Config {
    std::string name;
    std::string domain;
    std::string pubkey;
};

static std::vector<Config> g_configs;

// Helper: Read entire file from assets
static std::string readAssetFile(AAssetManager* mgr, const char* filename) {
    AAsset* asset = AAssetManager_open(mgr, filename, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGI("Failed to open asset: %s", filename);
        return "";
    }

    off_t length = AAsset_getLength(asset);
    if (length <= 0) {
        AAsset_close(asset);
        return "";
    }

    // Use a vector to ensure memory is contiguous and safe
    std::vector<char> fileData(length);
    int bytesRead = AAsset_read(asset, fileData.data(), length);
    AAsset_close(asset);

    if (bytesRead <= 0) {
        return "";
    }

    // Convert to string using the actual number of bytes read
    // This prevents accidental null-terminator issues in minified JSON
    return std::string(fileData.data(), bytesRead);
}

// Simple JSON parser for array of objects
static void parseDefaultConfigs(const std::string& jsonStr) {
    g_configs.clear();
    if (jsonStr.empty()) return;

    size_t pos = 0;
    while (true) {
        // 1. Find "name" key
        size_t nameKeyPos = jsonStr.find("\"name\"", pos);
        if (nameKeyPos == std::string::npos) break;

        // 2. Extract Name Value
        size_t nameColon = jsonStr.find(":", nameKeyPos);
        size_t nameStart = jsonStr.find("\"", nameColon + 1);
        size_t nameEnd   = jsonStr.find("\"", nameStart + 1);
        if (nameStart == std::string::npos || nameEnd == std::string::npos) break;
        std::string name = jsonStr.substr(nameStart + 1, nameEnd - nameStart - 1);

        // 3. Extract Domain Value
        size_t domainKeyPos = jsonStr.find("\"domain\"", nameEnd);
        size_t domainColon  = jsonStr.find(":", domainKeyPos);
        size_t domainStart  = jsonStr.find("\"", domainColon + 1);
        size_t domainEnd    = jsonStr.find("\"", domainStart + 1);
        if (domainStart == std::string::npos || domainEnd == std::string::npos) break;
        std::string domain = jsonStr.substr(domainStart + 1, domainEnd - domainStart - 1);

        // 4. Extract Pubkey Value
        size_t pubkeyKeyPos = jsonStr.find("\"pubkey\"", domainEnd);
        size_t pubkeyColon  = jsonStr.find(":", pubkeyKeyPos);
        size_t pubkeyStart  = jsonStr.find("\"", pubkeyColon + 1);
        size_t pubkeyEnd    = jsonStr.find("\"", pubkeyStart + 1);
        if (pubkeyStart == std::string::npos || pubkeyEnd == std::string::npos) break;
        std::string pubkey = jsonStr.substr(pubkeyStart + 1, pubkeyEnd - pubkeyStart - 1);

        if (pubkey.find("00000000000") != std::string::npos || domain == "t.example.com") {
            LOGI("Skipping template config: %s", name.c_str());
            // Do not push to g_configs, just continue to next
            pos = pubkeyEnd + 1;
            continue;
        }

        // Store it
        Config cfg;
        cfg.name = name;
        cfg.domain = domain;
        cfg.pubkey = pubkey;
        g_configs.push_back(cfg);

        // Move cursor forward to search for the next object
        pos = pubkeyEnd + 1;
    }

//    LOGI("VaydnsConfig: Successfully parsed %zu configs", g_configs.size());
}

// ==================== JNI Functions ====================

extern "C"
JNIEXPORT void JNICALL
Java_com_net2share_vaydns_DefaultConfigs_loadConfigs(JNIEnv* env, jclass /*this*/,
                                                    jobject assetManager) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGI("Failed to get AssetManager");
        return;
    }

    std::string jsonStr = readAssetFile(mgr, "default_configs.json");
    if (jsonStr.empty()) {
        LOGI("default_configs.json not found or empty → 0 configs loaded");
        return;
    }

    parseDefaultConfigs(jsonStr);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_net2share_vaydns_DefaultConfigs_getConfigCount(JNIEnv* /*env*/, jclass /*this*/) {
    return static_cast<jint>(g_configs.size());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_net2share_vaydns_DefaultConfigs_getConfigName(JNIEnv* env, jclass /*this*/, jint index) {
    if (index < 0 || index >= g_configs.size()) return env->NewStringUTF("");
    return env->NewStringUTF(g_configs[index].name.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_net2share_vaydns_DefaultConfigs_getConfigDomain(JNIEnv* env, jclass /*this*/, jint index) {
    if (index < 0 || index >= g_configs.size()) return env->NewStringUTF("");
    return env->NewStringUTF(g_configs[index].domain.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_net2share_vaydns_DefaultConfigs_getConfigPubkey(JNIEnv* env, jclass /*this*/, jint index) {
    if (index < 0 || index >= g_configs.size()) return env->NewStringUTF("");
    return env->NewStringUTF(g_configs[index].pubkey.c_str());
}
