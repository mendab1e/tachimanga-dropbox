# Vendored compile-time host API

These original source files are used only by `compileOnly(project(":host-api"))`. They must never be packaged into the APK. Tachimanga/Mihon supplies the runtime implementations. The unused `LocalScoped` class at the end of Injekt `api/Scope.kt` is omitted because its Kotlin 1.0-era inline/protected calls do not compile under Kotlin 1.7. No declarations used by this extension have been changed.

https://github.com/tachiyomiorg/extensions-lib
Commit: 11506a7390d9d57578753948c7799e2ffeee5819

https://github.com/inorichi/injekt
Commit: 65b04400cf379aa952dce00443f57b81f9a936ea


The extensions-lib subset contains Source, CatalogueSource, ConfigurableSource, HttpSource, NetworkHelper and source models. Injekt contains its API and core Kotlin sources. See licenses/.
