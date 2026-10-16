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

# Bouncy Castle loads algorithm mappings through class-name strings at runtime;
# R8 cannot infer that reflective edge and may otherwise strip EdEC$Mappings.
# Keep the provider, the Ed25519 mapping registry, and its SPI implementations.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EdEC$Mappings { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
