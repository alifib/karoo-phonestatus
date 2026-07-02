# Add project-specific ProGuard rules here.
# karoo-ext uses kotlinx.serialization; keep serializers if you enable minification.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
