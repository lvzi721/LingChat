# sherpa-onnx 的原生库通过 JNI 按「字段名」反射读取配置类
# （例如 KeywordSpotterConfig.maxActivePaths）。App 的 release 构建开启了
# R8 压缩，字段名被改写后会直接抛：
#   Failed to get field ID for maxActivePaths
# 官方 AAR 未附带 proguard 规则，因此这里以 consumer 规则形式导出给宿主 App。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

