-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod,Signature
-allowaccessmodification

-keep class com.familylocation.client.MainActivity { *; }
-keep class com.familylocation.client.KeepAliveService { *; }
-keep class com.familylocation.client.BootReceiver { *; }
-keep class com.familylocation.client.LocalApkProvider { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.content.ContentProvider { *; }
-keep class com.familylocation.client.R { *; }
-keep class com.familylocation.client.R$* { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontwarn org.json.**