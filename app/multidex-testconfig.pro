# REQUIRED by MultiDex: This file defines which classes are
# in the primary DEX file, and thus loaded first.  It MUST
# include everything needed to load any secondary DEX files.

# Keep the MultiDex classes themselves
-keep class android.support.multidex.** { *; }
-keep class androidx.multidex.** { *; }

# Keep everything referenced from the main application class
# which installs MultiDex
-keep class com.xmission.trevin.android.notes.NoteApplication { *; }
-keep class android.os.Build { *; }
-keep class android.util.Log { *; }
-keep class androidx.startup.** { *; }
-keep class androidx.work.** { *; }

# Keep the Java 8+ desugaring library classes (j$)
## These are essential for the application to run on API < 21
-keep class j$.** { *; }
-keep interface j$.** { *; }

# Keep our custom Test Runner
-keep class com.xmission.trevin.android.notes.util.MultiDexJUnitRunner { *; }

# Keep standard test runner and instrumentation classes
-keep class android.support.test.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.test.internal.** { *; }
-keep class androidx.tracing.** { *; }
