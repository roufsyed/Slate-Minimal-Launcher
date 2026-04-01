# Keep all app classes (fixed from incorrect com.minimal.launcher)
-keep class com.slate.launcher.** { *; }

# Keep enum values used in GestureAction sealed class serialization
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable (standard Android boilerplate)
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
