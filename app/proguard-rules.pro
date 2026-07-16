# smbj uses reflection/BouncyCastle; keep it intact if minification is ever enabled.
-keep class com.hierynomus.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
