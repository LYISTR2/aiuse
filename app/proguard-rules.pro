# R8/ProGuard 规则
#
# 当前 release 构建未开启 minify（isMinifyEnabled = false），此文件暂不生效。
# 若将来启用混淆，以下规则用于保护序列化与加密相关的反射路径。

# kotlinx.serialization：保留 @Serializable 类的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class ltd.usage.tracker.** {
    *** Companion;
}
-keepclasseswithmembers class ltd.usage.tracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ltd.usage.tracker.**$$serializer { *; }

# 数据模型与凭据：反射构造，不可裁剪
-keep class ltd.usage.tracker.data.** { *; }
-keep class ltd.usage.tracker.ledger.** { *; }

# AndroidKeyStore / JCE provider 按名称查找，不可混淆
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# OkHttp 可选依赖（缺失时仅告警，不影响运行）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
