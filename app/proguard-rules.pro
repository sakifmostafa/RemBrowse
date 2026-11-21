# Release builds are shrunk only: unused classes are removed, but nothing is renamed or
# rewritten, so stack traces stay readable and behaviour matches the debug build.
-dontobfuscate
-dontoptimize

# GeckoView is driven from native code and by reflection; keep all of it.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }

# SnakeYAML (a GeckoView dependency) refers to desktop-Java bean classes that Android does not
# have. That code path is never reached on Android.
-dontwarn java.beans.**

# GeckoView parses its startup configuration file (see EngineConfig) with SnakeYAML, which
# finds fields and constructors by reflection. Keep it whole, so nothing it needs at startup
# can be shrunk away.
-keep class org.yaml.snakeyaml.** { *; }

# The SSH library (ConnectBot sshlib, Trilead's API) creates its ciphers, MACs and key
# exchanges from class names. Keep it, and the post-quantum key exchange code it calls, whole,
# so a release build connects exactly like a debug one.
-keep class com.trilead.ssh2.** { *; }
-keep class org.connectbot.** { *; }
-keep class asia.hombre.** { *; }

# sshlib uses only Tink's Ed25519 and X25519 primitives, which it calls directly (so they are
# kept). Tink's keysets, JWT and key downloading need protobuf, Gson and Google's HTTP client,
# which sshlib leaves out on purpose; that code is never reached.
-dontwarn com.google.protobuf.**
-dontwarn com.google.gson.**
-dontwarn com.google.api.client.**
