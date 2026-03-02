-keep class com.domainvoyager.app.data.** { *; }
-keepattributes *Annotation*
-keepclassmembers class ** {
    @androidx.room.* *;
}
