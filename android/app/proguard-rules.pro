# Compose, AndroidX, WorkManager, OkHttp and ML Kit ship their own consumer rules. The only
# reflection into app code is WorkManager: it stores the Worker class name in its database and
# instantiates it later by name, so keep the constructor reachable by its original signature.
-keep class com.tanmay.securehome.sms.ShareWorker { public <init>(...); }

# Keep source positions so stack traces from sideloaded builds are readable.
-keepattributes SourceFile,LineNumberTable
