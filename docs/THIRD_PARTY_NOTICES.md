# Third-party notices

Project source is Apache-2.0. Dependencies retain their own terms; this is not a blanket relicensing statement.

| Component | Use | License / source |
| --- | --- | --- |
| Gradle 8.11.1 wrapper | Build launcher; vendored wrapper and original scripts | [Apache-2.0](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE); source headers retained |
| Kotlin 2.1.20 and Kotlin Gradle/Compose compiler plugins | Language and build tooling | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/v2.1.20/license/LICENSE.txt) |
| kotlinx.coroutines | Native asynchronous jobs, state streams, and Play Services adapters | [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| Android Gradle Plugin 8.9.2 | Android build tooling | [Apache-2.0](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/LICENSE) |
| AndroidX Compose, Activity, Core, Wear Compose, and Lifecycle | Native UI and Android utilities | [Apache-2.0](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt); individual artifacts may include additional notices |
| Google Play Services Wearable | Phone/watch Data Layer | [Google Android SDK terms](https://developer.android.com/studio/terms) and [third-party notices](https://developers.google.com/android/guides/opensource); not Apache-2.0 project code |
| JUnit 4.13.2 | JVM tests | [Eclipse Public License 1.0](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |
| Robolectric 4.14.1 | Local Android/Compose tests; not packaged in the apps | [MIT, with Apache-2.0 portions](https://github.com/robolectric/robolectric/blob/robolectric-4.14.1/LICENSE) |
| Node.js built-in modules | Local bridge runtime; not vendored | [Node.js license](https://github.com/nodejs/node/blob/main/LICENSE), with retained upstream third-party terms |

Build-time/transitive artifacts are resolved from Google Maven, Maven Central, and the Gradle Plugin Portal. See Gradle dependency reports for the resolved graph. A production binary release must review its full resolved dependency graph and retained notices. This foundation publishes developer APKs rather than a production/store release.

The character, colors, interface layout, queue logic, and bridge harness are original project work. No Apple Watch demo assets, ChatGPT voice UI, or private application code is incorporated.
