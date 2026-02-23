# proguard-rules.pro для Minima Wallet

# Основные настройки
-dontobfuscate
-dontoptimize
-allowaccessmodification

# Исключаем проблемные классы AndroidIDE
-dontwarn com.itsaky.androidide.**
-keep class com.itsaky.androidide.** { *; }
-dontnote com.itsaky.androidide.**

# Удаляем логи в production
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Сохраняем все классы Minima
-keep class org.minima.** { *; }
-keepclassmembers class org.minima.** { *; }

# Сохраняем нативные методы
-keepclasseswithmembernames class * {
    native <methods>;
}

# Сохраняем конструкторы Activity, Service и т.д.
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Сохраняем ViewBinding
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(...);
    public static * bind(...);
}

# Сохраняем все классы приложения
-keep class com.example.minimawallet.** { *; }

# Сохраняем JSON сериализацию
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# AndroidX библиотеки
-keep class androidx.** { *; }
-keepclassmembers class androidx.** { *; }

# Material Design
-keep class com.google.android.material.** { *; }
-keepclassmembers class com.google.android.material.** { *; }

# Jackson JSON
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class com.fasterxml.jackson.** { *; }

# Биометрическая аутентификация
-keep class androidx.biometric.** { *; }

# Сохраняем все методы с аннотациями
-keepattributes *Annotation*

# Сохраняем имена классов для отладки
-keepnames class ** { *; }

# Сохраняем имена методов для рефлексии
-keepclassmembernames class ** {
    public *;
}