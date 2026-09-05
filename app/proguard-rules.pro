# Add project specific ProGuard rules here.
#
# Minification is currently off (isMinifyEnabled = false). These rules exist so
# that turning R8 on later does not break the Gson-based folder-list codec in
# data/BackupGroup.kt (TypeToken<List<String>> needs generic signatures).
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
