# JGit uses reflection for config, transports and NLS bundles.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
# JSch loads ciphers/kex/signature algorithms by class name.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
# Optional dependencies referenced by JGit/JSch but unused on Android.
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn java.lang.ProcessHandle
