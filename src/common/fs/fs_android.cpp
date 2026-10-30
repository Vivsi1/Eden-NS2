// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include "common/android/android_common.h"
#include "common/android/id_cache.h"
#include "common/assert.h"
#include "common/fs/fs_android.h"
#include "common/string_util.h"

#include <android/log.h>

#define SAF_LOG(...) __android_log_print(ANDROID_LOG_INFO, "EdenSAF", __VA_ARGS__)

namespace Common::FS::Android {

void RegisterCallbacks(JNIEnv* env, jclass clazz) {
    env->GetJavaVM(&g_jvm);
    native_library = clazz;

    s_get_parent_directory = env->GetStaticMethodID(native_library, "getParentDirectory",
                                                    "(Ljava/lang/String;)Ljava/lang/String;");
    s_get_filename = env->GetStaticMethodID(native_library, "getFilename",
                                            "(Ljava/lang/String;)Ljava/lang/String;");
    s_get_size = env->GetStaticMethodID(native_library, "getSize", "(Ljava/lang/String;)J");
    s_is_directory = env->GetStaticMethodID(native_library, "isDirectory", "(Ljava/lang/String;)Z");
    s_file_exists = env->GetStaticMethodID(native_library, "exists", "(Ljava/lang/String;)Z");
    s_open_content_uri = env->GetStaticMethodID(native_library, "openContentUri",
                                                "(Ljava/lang/String;Ljava/lang/String;)I");
    s_get_directory_entries = env->GetStaticMethodID(native_library, "getDirectoryEntries",
                                                      "(Ljava/lang/String;)[Ljava/lang/String;");
}

void UnRegisterCallbacks() {
    s_get_parent_directory = nullptr;
    s_get_filename = nullptr;

    s_get_size = nullptr;
    s_is_directory = nullptr;
    s_file_exists = nullptr;

    s_open_content_uri = nullptr;
    s_get_directory_entries = nullptr;
}

bool IsContentUri(const std::string& path) {
    constexpr std::string_view prefix = "content://";
    if (path.size() < prefix.size()) [[unlikely]] {
        return false;
    }

    return path.find(prefix) == 0;
}

s32 OpenContentUri(const std::string& filepath, OpenMode openmode) {
    SAF_LOG("OpenContentUri path=%s", filepath.c_str());
    if (s_open_content_uri == nullptr) {
        SAF_LOG("OpenContentUri callback is null");
        return -1;
    }

    const char* mode = "";
    switch (openmode) {
    case OpenMode::Read:
        mode = "r";
        break;
    default:
        UNIMPLEMENTED();
        return -1;
    }
    auto env = Common::Android::GetEnvForThread();
    jstring j_filepath = Common::Android::ToJString(env, filepath);
    jstring j_mode = Common::Android::ToJString(env, mode);
    return env->CallStaticIntMethod(native_library, s_open_content_uri, j_filepath, j_mode);
}

u64 GetSize(const std::string& filepath) {
    if (s_get_size == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    const auto result = static_cast<u64>(env->CallStaticLongMethod(
        native_library, s_get_size,
        Common::Android::ToJString(env, filepath)));
    SAF_LOG("GetSize path=%s result=%llu", filepath.c_str(),
            static_cast<unsigned long long>(result));
    return result;
}

bool IsDirectory(const std::string& filepath) {
    if (s_is_directory == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    return env->CallStaticBooleanMethod(
        native_library, s_is_directory,
        Common::Android::ToJString(Common::Android::GetEnvForThread(), filepath));
}

bool Exists(const std::string& filepath) {
    if (s_file_exists == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    return env->CallStaticBooleanMethod(
        native_library, s_file_exists,
        Common::Android::ToJString(Common::Android::GetEnvForThread(), filepath));
}

std::string GetParentDirectory(const std::string& filepath) {
    if (s_get_parent_directory == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    jstring j_return = static_cast<jstring>(env->CallStaticObjectMethod(
        native_library, s_get_parent_directory, Common::Android::ToJString(env, filepath)));
    if (!j_return) {
        return {};
    }
    return Common::Android::GetJString(env, j_return);
}

std::string GetFilename(const std::string& filepath) {
    if (s_get_filename == nullptr) {
        return 0;
    }
    auto env = Common::Android::GetEnvForThread();
    jstring j_return = static_cast<jstring>(env->CallStaticObjectMethod(
        native_library, s_get_filename, Common::Android::ToJString(env, filepath)));
    if (!j_return) {
        return {};
    }
    return Common::Android::GetJString(env, j_return);
}

std::vector<std::string> GetDirectoryEntries(const std::string& filepath) {
    SAF_LOG("GetDirectoryEntries path=%s", filepath.c_str());
    std::vector<std::string> entries;
    if (s_get_directory_entries == nullptr) {
        return entries;
    }
    auto env = Common::Android::GetEnvForThread();
    auto j_filepath = Common::Android::ToJString(env, filepath);
    auto j_entries = static_cast<jobjectArray>(
        env->CallStaticObjectMethod(native_library, s_get_directory_entries, j_filepath));
    if (j_entries == nullptr) {
        env->DeleteLocalRef(j_filepath);
        return entries;
    }
    const auto count = env->GetArrayLength(j_entries);
    entries.reserve(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto j_entry = static_cast<jstring>(env->GetObjectArrayElement(j_entries, i));
        if (j_entry != nullptr) {
            entries.emplace_back(Common::Android::GetJString(env, j_entry));
            env->DeleteLocalRef(j_entry);
        }
    }
    env->DeleteLocalRef(j_entries);
    env->DeleteLocalRef(j_filepath);
    SAF_LOG("GetDirectoryEntries path=%s count=%zu", filepath.c_str(), entries.size());
    return entries;
}

} // namespace Common::FS::Android
