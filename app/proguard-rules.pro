# Don't obfuscate anything in our app.  This MUST be paired with
# "android.enableR8.fullMode=false" in the gradle.properties file.
-keep class com.xmission.trevin.android.** { *; }
# Prevent R8 from moving classes into different packages
-keeppackagenames com.xmission.trevin.android.**
# Keep attributes required for reflection and debugging
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
