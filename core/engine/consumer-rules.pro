# JNI methods are resolved by their class and method names from native code.
-keep class app.croptop.core.engine.NativeEngine { native <methods>; }
-keep class app.croptop.core.engine.NativeCanvasView { native <methods>; }
-keep class app.croptop.core.engine.NativeVulkanFrameBridge { native <methods>; }
