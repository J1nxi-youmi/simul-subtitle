plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    // 1.8+: its skiko no longer sets the window shadow while creating a transparent window
    id("org.jetbrains.compose") version "1.8.2" apply false
}
