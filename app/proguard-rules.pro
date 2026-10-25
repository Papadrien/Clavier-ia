# Règles R8 de Taipo (lot 2.1 de la revue de code).
#
# Principe : conserver tout ce qui est appelé PAR NOM depuis du code natif (JNI) ou par réflexion.
# Règles volontairement larges (on garde les paquets entiers) : plus sûres qu'optimales. À resserrer
# seulement après test d'un APK release (voir docs/release.md).

# Traces de pile lisibles (numéros de ligne) pour les rapports de plantage.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# sherpa-onnx : le code natif (libsherpa-onnx-jni.so) lit les champs des classes de configuration
# (OnlineRecognizerConfig, EndpointConfig...) et appelle les méthodes natives par nom.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM : bibliothèque JNI (liblitertlm_jni.so) qui retrouve ses classes Kotlin/Java par nom.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# SQLCipher : classes appelées depuis le code natif (libsqlcipher.so).
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# Les méthodes natives ne doivent jamais être renommées.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Room (entités, DAO, base) : les règles de consommateur de androidx.room sont fournies par la
# bibliothèque ; rien à ajouter ici tant que les classes Room restent déclarées normalement.
