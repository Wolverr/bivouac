# JNI entry points and the callback interface are looked up by name from native code.
-keep class io.github.wolverr.bivouac.llm.NativeEngine { *; }
-keep interface io.github.wolverr.bivouac.llm.NativeEngine$Listener { *; }
-keepclassmembers class * implements io.github.wolverr.bivouac.llm.NativeEngine$Listener { *; }
