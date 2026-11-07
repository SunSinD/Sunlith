-keep class biweekly.** { *; }
-keep class com.github.mangstadt.** { *; }
-dontwarn org.jsoup.**

# Biweekly's optional jCal JSON adapter is not used by the ICS-only importer/exporter.
-dontwarn com.fasterxml.jackson.annotation.JsonFormat
-dontwarn com.fasterxml.jackson.databind.DeserializationContext
-dontwarn com.fasterxml.jackson.databind.JsonDeserializer
-dontwarn com.fasterxml.jackson.databind.JsonSerializer
-dontwarn com.fasterxml.jackson.databind.SerializerProvider
-dontwarn com.fasterxml.jackson.databind.module.SimpleModule
-dontwarn com.fasterxml.jackson.databind.ser.std.StdSerializer
