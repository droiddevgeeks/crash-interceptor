# ==============================================================================
# crashsink — library self-minification rules
#
# These apply only if the crashsink module itself is minified during ITS OWN build
# (most library AARs ship un-minified and let the consuming app's R8 do the work).
# Wire up in an Android library module with:
#
#     android {
#         buildTypes {
#             release {
#                 // usually false for a library; enable only if you minify the AAR
#                 // isMinifyEnabled = true
#                 proguardFiles(
#                     getDefaultProguardFile("proguard-android-optimize.txt"),
#                     "proguard-rules.pro")
#             }
#         }
#     }
# ==============================================================================

# Preserve the public API surface in the published artifact.
-keep public class com.droiddevgeeks.crashsink.CrashReporter { public *; }
-keep public interface com.droiddevgeeks.crashsink.CrashSink { *; }

# crashsink uses no reflection/JNI; no other keeps are required. Internal classes
# (CrashProcessor, CrashFileStore, CrashInterceptor, etc.) may be freely shrunk
# and obfuscated — they are never looked up by name.
