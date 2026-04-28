# Project models — kept whole because they're (de)serialised by reflection.
-keep class me.nettrash.geo.data.model.** { *; }
-keepattributes *Annotation*

# ----------------------------------------------------------------------
# ARCore + Sceneview + Filament keep rules.
#
# Without these, R8 rewrites ARCore's internal classes (com.google.ar.core.*)
# in a way that corrupts their stack-map tables and produces verifier
# errors like:
#   Invalid stack map table at instruction index 4: invokespecial
#   Ljava/lang/Enum;<init>(Ljava/lang/String;I)V, error: Constructor
#   mismatch, expected constructor from com.google.ar.core.x, but was
#   java.lang.Enum.<init>(java.lang.String, int).
#
# These crash on first launch of the AR view. The fix is to keep ARCore,
# its Filament rendering backend, and the Sceneview wrapper all whole and
# silence the related warnings.
# ----------------------------------------------------------------------
-keep class com.google.ar.** { *; }
-keep interface com.google.ar.** { *; }
-keep enum com.google.ar.** { *; }
-dontwarn com.google.ar.**

# Sceneview (io.github.sceneview:arsceneview) — uses reflection internally
# to wire ARCore frames into Filament render targets.
-keep class io.github.sceneview.** { *; }
-keep interface io.github.sceneview.** { *; }
-dontwarn io.github.sceneview.**

# Filament — renders the AR scene; its native bindings refuse to load if
# their Java mirrors are obfuscated.
-keep class com.google.android.filament.** { *; }
-keep interface com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**

# Sceneform (legacy ARCore Java SDK) — pulled in transitively.
-keep class com.google.ar.sceneform.** { *; }
-dontwarn com.google.ar.sceneform.**

# Native methods on any class — required for ARCore + Filament JNI shims.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ----------------------------------------------------------------------
# kotlinx.serialization — keep generated `*$$serializer` companions and
# any class annotated @Serializable so reflection-based instantiation
# works after R8.
# ----------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
    @kotlinx.serialization.Serializable <fields>;
}
