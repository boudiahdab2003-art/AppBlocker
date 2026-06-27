# R8/ProGuard rules for AppBlocker release builds.
# AGP already keeps manifest-declared components and bundles the Compose/Room consumer rules;
# these are explicit belt-and-suspenders keeps for the classes the system instantiates by name.

# Components referenced from the manifest / system (accessibility, install receiver, device admin).
-keep class com.appblocker.service.BlockerAccessibilityService { *; }
-keep class com.appblocker.service.PackageInstallReceiver { *; }
-keep class com.appblocker.admin.AppBlockerAdminReceiver { *; }

# Room entities/DAOs are accessed by generated code/reflection — keep their shape.
-keep class com.appblocker.data.AppRule { *; }
-keep class com.appblocker.data.FocusState { *; }
-keep class com.appblocker.data.BlockedKeyword { *; }
-keep class com.appblocker.data.Schedule { *; }
-keep @androidx.room.Entity class * { *; }

# Keep enum values (used via valueOf in the Room TypeConverters).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
