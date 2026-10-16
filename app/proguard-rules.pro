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

# Ed25519 services are located by algorithm/provider registration in Bouncy Castle.
# Keep provider registration and the EdEC key/signature SPI reachable after R8.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
