# Keep native JNI methods and C-ABI bridge
-keepclasseswithmembernames class org.bobrdobr.vpn.core.engine.JniBridge {
    native <methods>;
}

# Keep models used across JNI
-keep class org.bobrdobr.vpn.core.model.TunnelStats {
    <init>(...);
    <fields>;
}

# Keep SocketProtector callback interface
-keep interface org.bobrdobr.vpn.core.service.SocketProtector {
    public boolean protectSocket(int);
}

-keep interface org.bobrdobr.vpn.core.engine.JniBridge$StatusListener {
    public void onStatusChanged(int, java.lang.String);
}
