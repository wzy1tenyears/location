-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod,Signature
-allowaccessmodification
-repackageclasses ''
-overloadaggressively
-adaptclassstrings

-keep public class com.familylocation.admin.AdminActivity {
    public <init>();
    protected void onCreate(android.os.Bundle);
}
-keep class com.familylocation.admin.R { *; }
-keep class com.familylocation.admin.R$* { *; }

-dontwarn org.json.**
-keep public class com.familylocation.admin.AdminApkProvider { public <init>(); }
-keep public class * extends android.content.ContentProvider
