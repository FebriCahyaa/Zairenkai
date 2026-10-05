# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.zairenkai.app.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.zairenkai.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.zairenkai.app.data.**$$serializer { *; }
