-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod
-allowaccessmodification
-repackageclasses ''

-keep public class com.familylocation.client.MainActivity
-keep public class com.familylocation.client.KeepAliveService
-keep public class com.familylocation.client.BootReceiver
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep class com.familylocation.client.R { *; }
-keep class com.familylocation.client.R$* { *; }

-keepclassmembers class com.familylocation.client.MainActivity {
    public void onRequestPermissionsResult(int, java.lang.String[], int[]);
}

-dontwarn org.json.**

-keep public class com.familylocation.client.LocalApkProvider { public <init>(); }
-keep public class * extends android.content.ContentProvider
