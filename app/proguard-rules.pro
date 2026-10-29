# ==============================================================================
# PROGUARD / R8 RULES - SMART STUDENT ID (SEC-06 & OPT-08)
# ==============================================================================

# Preserve model classes for Gson JSON serialization/deserialization
-keep class com.example.smartstudentid.model.** { *; }

# Keep source file and line numbers for stack trace debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Strip debug/verbose/info logs in release builds (MASVS-STORAGE)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
