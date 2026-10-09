# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class jr.brian.thorhud.**$$serializer { *; }
-keepclassmembers class jr.brian.thorhud.** {
    *** Companion;
}
-keepclasseswithmembers class jr.brian.thorhud.** {
    kotlinx.serialization.KSerializer serializer(...);
}
