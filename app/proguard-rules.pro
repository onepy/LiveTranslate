# Instantiated by name inside the Shizuku user-service process.
-keep class com.livetranslate.shizuku.ShellService { <init>(...); *; }
-keep class com.livetranslate.shizuku.IShellService { *; }
-keep class com.livetranslate.shizuku.IShellService$* { *; }

# Keeps crash traces in the in-app diagnostics readable without a mapping file.
-keepattributes SourceFile,LineNumberTable
-keepnames class com.livetranslate.** { *; }

# OkHttp optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
