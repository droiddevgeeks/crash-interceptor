# ==============================================================================
# TEMPLATE — consumer-rules.pro for an SDK that EMBEDS crashsink
#
# Copy this into YOUR SDK module (the AAR you ship), NOT into the host app and
# NOT into crashsink. Replace `com.yoursdk` with your SDK's real root package.
#
# Wire it up in your SDK's build.gradle(.kts):
#
#     android { defaultConfig { consumerProguardFiles("consumer-rules.pro") } }
#
# Because it lives in YOUR AAR, R8 applies it automatically to every host app
# that depends on you. The host app adds nothing. This is the whole point:
# crashsink attributes crashes by matching stack-frame packages against the
# `ownedPrefix` you pass to CrashReporter.create(...) — and only YOU know that
# package, so only YOU can ship the rule that keeps it matching after R8.
# ==============================================================================

# ------------------------------------------------------------------------------
# REQUIRED — keep attribution working after the host app obfuscates your SDK.
#
# R8 runs at the HOST APP's build time and renames your classes to `a.b.c`.
# Once that happens, `ownedPrefix = "com.yoursdk."` no longer matches the runtime
# frames, and your crashes are SILENTLY not captured (no error — just nothing).
# Keeping the package names preserves the prefix match. Class names within the
# package may still be shortened; attribution matches the package prefix, not the
# full class name, so that is fine.
# ------------------------------------------------------------------------------
-keeppackagenames com.yoursdk.**

# Readable line numbers + source file in the stack traces crashsink captures.
# (crashsink forwards the raw trace to your CrashSink; if the host minifies, the
#  class names in that trace stay obfuscated — retain the host's release
#  mapping.txt to symbolicate on your backend.)
-keepattributes SourceFile,LineNumberTable

# ------------------------------------------------------------------------------
# ALTERNATIVE (bulletproof) — do not obfuscate your package at all. Heavier
# (that package is neither renamed nor shrunk), but the traces crashsink hands
# your CrashSink are then human-readable with no server-side mapping needed.
# Use this INSTEAD of -keeppackagenames above, not in addition.
# ------------------------------------------------------------------------------
#-keep class com.yoursdk.** { *; }
