-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod,Signature
-allowaccessmodification

-keep class com.familylocation.admin.AdminActivity { *; }
-keep class com.familylocation.admin.AdminApkProvider { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.content.ContentProvider { *; }
-keep class com.familylocation.admin.R { *; }
-keep class com.familylocation.admin.R$* { *; }

-dontwarn org.json.**