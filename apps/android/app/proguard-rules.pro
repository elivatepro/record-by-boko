# Plaud SDK: already obfuscated, and it uses Gson models and JNI by name. Don't shrink it further.
-keep class sdk.** { *; }
-keep class com.tinnotech.** { *; }
-keep class com.plaud.sdk.** { *; }
-dontwarn sdk.**
-dontwarn com.tinnotech.**
-dontwarn com.plaud.sdk.**
