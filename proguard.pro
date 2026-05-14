# Keep the entry point  traces reachable code
-keep class com.bitsycore.ktc.MainKt {
    public static void main(java.lang.String[]);
}

-repackageclasses 'com.bitsycore.internal'