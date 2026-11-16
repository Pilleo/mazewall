#pragma once

#include <jni.h>
#include "live_startup_binding.hpp"

namespace mazewall::profiler::integrity {
// Compares actual Java observations to the native-captured immutable manifest.
bool compare_java_observation(JNIEnv* env, jobjectArray input, const Manifest& manifest);
}
