# Keep the entry point  traces reachable code
-keep class com.bitsycore.MainKt {
    public static void main(java.lang.String[]);
}

-optimizationpasses 5
-optimizeaggressively
-mergeinterfacesaggressively
-allowaccessmodification
-repackageclasses 'com.bitsycore.internal'
-optimizations *