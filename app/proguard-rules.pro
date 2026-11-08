# Instantiated by name inside the Shizuku user-service process.
-keep class com.livetranslate.shizuku.ShellService { <init>(...); *; }
-keep class com.livetranslate.shizuku.IShellService { *; }
-keep class com.livetranslate.shizuku.IShellService$* { *; }

# OkHttp optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
