# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk/tools/proguard/proguard-android.txt

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses

# Room
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# Pikafish Engine bridge
-keep class com.zhongguoxiangqi.engine.** { *; }
-keepclassmembers class com.zhongguoxiangqi.engine.** {
    public *;
}

# Game logic
-keep class com.zhongguoxiangqi.game.** { *; }