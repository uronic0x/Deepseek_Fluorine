# Keep JNI bridge methods and callback interface for libfluorine.so
-keep class com.uronic0x.fluorine.bridge.FluorineBridge { *; }
-keep interface com.uronic0x.fluorine.bridge.JniStreamCallback { *; }
