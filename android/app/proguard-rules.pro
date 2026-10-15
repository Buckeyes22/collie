# Kotlin serialization resolves serializers from generated companion objects.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNI entry is resolved by its exact class and method name.
-keep class com.lateapex.collie.ui.BoundedWoff2Decoder { native <methods>; }
