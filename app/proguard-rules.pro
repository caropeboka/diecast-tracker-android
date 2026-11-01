# Room, kotlinx.serialization, Compose and Coil ship their own consumer rules.

# ML Kit ("Foto studio") creates its component registrars by reflection, named in its manifest meta-data. Its own
# rule keeps the classes only, and R8's full mode then drops their no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
