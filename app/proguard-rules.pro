# ProGuard / R8 configuration for DacVolumeFix
# Optimized for R8 Full Mode (android.enableR8.fullMode=true)

# Keep Coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Android OS hidden/native reflection calls (e.g. Os.ioctlInt, FileDescriptor)
-keepclassmembers class java.io.FileDescriptor {
    private int descriptor;
}
-dontwarn android.system.Os
-keepclassmembers class android.system.Os {
    public static int ioctlInt(java.io.FileDescriptor, int);
}
