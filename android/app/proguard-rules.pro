# Retrofit/OkHttp: keep the generic signatures the converters reflect over.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Retrofit interfaces are only referenced reflectively.
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization interface <1> { @retrofit2.http.* public *** *(...); }

# kotlinx.serialization generates a companion serializer per @Serializable type;
# stripping it turns every API response into a runtime crash.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nutrilens.**$$serializer { *; }
-keepclassmembers class com.nutrilens.** {
    *** Companion;
}

# Room generates implementations that are looked up by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Enum values are read by name from persisted rows and JSON.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Crash reports are unreadable without these.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
