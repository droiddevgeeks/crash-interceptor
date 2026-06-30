# ==============================================================================
# crashsink — consumer ProGuard/R8 rules
#
# These rules are bundled into the published AAR and applied automatically to the
# R8/ProGuard run of any app (or downstream SDK) that depends on crashsink.
# Wire them up in an Android library module with:
#
#     android { defaultConfig { consumerProguardFiles("consumer-rules.pro") } }
# ==============================================================================

# crashsink uses no reflection, JNI, or by-name serialization, so it needs no
# special keep rules to function. We keep only the public entry points, so they
# survive aggressive shrinking even if reached via DI/reflection.
-keep public class com.droiddevgeeks.crashsink.CrashReporter { public *; }
-keep public interface com.droiddevgeeks.crashsink.CrashSink { *; }

# ------------------------------------------------------------------------------
# IMPORTANT — attribution under obfuscation (action required by the integrator)
#
# crashsink decides a crash is "yours" by matching each stack frame's package
# against the ownedPrefix you pass to CrashReporter.create(...). R8 obfuscates
# package names by default, which turns "com.example.sdk.Foo" into "a.b.c" at
# runtime — so the prefix no longer matches and YOUR CRASHES ARE SILENTLY NOT
# CAPTURED (no error, just nothing).
#
# In the module that OWNS the code you want attributed, add ONE of the following
# (replace the package with your own root):
#
#   # Lightweight: keep package names; classes may still be shortened. Enough for
#   # attribution, which matches on the package prefix, not the full class name.
#   -keeppackagenames com.example.sdk.**
#
#   # Bulletproof: do not obfuscate that package at all (no shrinking of it either).
#   #-keep class com.example.sdk.** { *; }
#
# And keep readable stack traces (line numbers / source file) in captured crashes:
#   -keepattributes SourceFile,LineNumberTable
#
# These are intentionally NOT enabled here: crashsink does not know your package,
# and forcing -keepattributes on every consumer's whole app would be overreach.
# ------------------------------------------------------------------------------
