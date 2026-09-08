# R8 and ProGuard read this file straight out of the jar, which is how a plain jar
# still ships the keep rules an AAR would carry in consumer-rules.pro.

# The envelope is serialised by property name. Renaming these getters renames the
# wire fields, and the ingest endpoint rejects the result.
-keep class com.accelhack.monica.MonicaEnvelope { *; }
-keep class com.accelhack.monica.MonicaEvent { *; }

# Stack traces are the product. Without these, every frame loses its file and line.
# InnerClasses and EnclosingMethod are for Jackson, whose ClassUtil looks at the
# enclosing class when it introspects a type; R8 full mode strips them otherwise.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# Jackson resolves these lazily and only on JVMs that have them.
-dontwarn com.fasterxml.jackson.databind.ext.**
-dontwarn java.beans.**

# monica-core also ships a JDK HttpClient transport for server applications. Nothing
# on Android instantiates it, but R8 still resolves the reference, and java.net.http
# does not exist on the platform.
-dontwarn java.net.http.**
