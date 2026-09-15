# osmdroid: reflective tile providers/archives
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
# usb-serial-for-android: drivers resolved via prober reflection
-keep class com.hoho.android.usbserial.** { *; }
-dontwarn com.hoho.android.usbserial.**
# kotlinx.serialization: keep serializers for payload models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.icegood.findmeinwood.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.icegood.findmeinwood.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# BLE callbacks invoked from framework
-keep class com.icegood.findmeinwood.transport.bluetooth.** { *; }
-keep class com.icegood.findmeinwood.transport.lora.** { *; }
