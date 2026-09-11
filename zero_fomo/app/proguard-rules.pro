# kotlinx.serialization keeps
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.arctechnology.zerofomo.data.network.** {
    *** Companion;
}
-keepclasseswithmembers class com.arctechnology.zerofomo.data.network.** {
    kotlinx.serialization.KSerializer serializer(...);
}
