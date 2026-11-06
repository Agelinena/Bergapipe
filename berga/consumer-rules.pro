# As classes do Bergapipe citadas no manifesto (Activity, Initializer) e os Workers já são
# mantidas pelas regras do Android, do App Startup e do WorkManager. Nada extra por enquanto.

# Telas citadas só pelo nome em XML de preferências (android:fragment), criadas por reflexão.
-keep class io.github.agelinena.bergapipe.backup.AutoBackupSettingsFragment { <init>(); }
# Lido pelo Cast SDK pelo nome (meta-dado OPTIONS_PROVIDER_CLASS_NAME no manifesto).
-keep class io.github.agelinena.bergapipe.cast.BergaCastOptionsProvider { <init>(); }
