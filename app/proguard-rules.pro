# Keep JNI bridge methods and nested callback interface + implementations for libfluorine.so
-keep class com.uronic0x.fluorine.bridge.FluorineBridge { *; }
-keep interface com.uronic0x.fluorine.bridge.FluorineBridge$JniStreamCallback { *; }
-keep class * implements com.uronic0x.fluorine.bridge.FluorineBridge$JniStreamCallback {
    public boolean onEvent(java.lang.String);
}
