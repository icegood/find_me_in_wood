# osmdroid: reflective tile providers/archives
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
# usb-serial-for-android: drivers resolved via prober reflection
-keep class com.hoho.android.usbserial.** { *; }
-dontwarn com.hoho.android.usbserial.**
# kotlinx.serialization: keep serializers for payload models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.findmeinwood.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class app.findmeinwood.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# BLE callbacks invoked from framework
-keep class app.findmeinwood.transport.bluetooth.** { *; }
-keep class app.findmeinwood.transport.lora.** { *; }
