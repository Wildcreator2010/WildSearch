# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.wildcreator.wildsearch.data.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.wildcreator.wildsearch.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
