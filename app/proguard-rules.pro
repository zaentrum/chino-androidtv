# Retrofit + OkHttp keep platform classes for reflection
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# kotlinx.serialization: keep generated $serializer fields on data classes
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

# AppAuth
-keep class net.openid.appauth.** { *; }
