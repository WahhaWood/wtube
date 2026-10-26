# R8 for the release build, after NewPipe's own rules (projects/_refs/NewPipe/app/proguard-rules.pro).

# Readable stack traces from the phone; shrinking is what saves the size.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# NewPipeExtractor looks up its "time ago" patterns by class name.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }

# Rhino runs YouTube's player JavaScript (signature and n-parameter deobfuscation) and generates classes at runtime.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**

-dontwarn okhttp3.**
-dontwarn okio.**

# The extractor's classes are Serializable and NewPipe keeps their fields the same way.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# The extractor builds playlist and channel continuations with protobuf-lite, which finds message fields by name.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ML Kit's init provider instantiates its component registrars by reflection (R8 logged NoSuchMethodException <init> in 0.20.3).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar { <init>(); }
