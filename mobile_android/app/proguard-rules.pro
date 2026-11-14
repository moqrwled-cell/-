# Proguard rules for LiveVideoTranslator
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.json.* <fields>;
    @org.json.* <methods>;
}
