# ARCore は JNI から参照されるクラスを持つため難読化しない。
-keep class com.google.ar.core.** { *; }
