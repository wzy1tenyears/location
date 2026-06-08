-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod
-allowaccessmodification
-repackageclasses ''

-keep public class com.familylocation.admin.AdminActivity
-keep public class * extends android.app.Activity
-keep class com.familylocation.admin.R { *; }
-keep class com.familylocation.admin.R$* { *; }

-dontwarn org.json.**
