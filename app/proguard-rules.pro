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
