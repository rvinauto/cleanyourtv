# dadb talks to the TV over a plain socket; keep its Kotlin metadata and okio from being stripped.
-keep class dadb.** { *; }
-keep class okio.** { *; }
-dontwarn org.slf4j.**
