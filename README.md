# TimeLock (Android Digital Wellbeing & Focus Lock)

TimeLock is an intentional digital wellbeing Android application built with Kotlin, Jetpack Compose, and Clean Architecture. It breaks unconscious app-hopping and compulsive doom-scrolling by enforcing pre-commitments, ambient time awareness, and hard global cooldowns.

---

## 💡 Core Problems & Solutions

* **The "App-Hopping" Loophole (Dopamine Substitution)**
  * *Problem*: Users locked out of YouTube instinctively switch to Bilibili, Instagram, or games, continuing the dopamine feedback loop.
  * *Solution*: **Global Shared Session & Cooldown**. Exiting or timing out on any monitored entertainment app triggers a system-wide lockout across all restricted apps.
* **OS Deep-Sleep Freezes & Timer Drift**
  * *Problem*: Standard in-memory timers (Coroutines/Handler delay) drift or freeze when OEMs (MIUI/HyperOS) throttle background services in Doze mode.
  * *Solution*: **Persistent SQLite/Room Heartbeat**. A persistent session database anchors timestamps against real system clocks, reconciling actual vs expected duration upon resume.
* **Single-Use Emergency Passes**
  * *Problem*: Users occasionally need to respond to messages or open shared links without being completely barred.
  * *Solution*: A strict **60-Second Quick Reply Pass** equipped with a single-use token to prevent infinite loop exploitation.

---

## 🛠️ Architecture & Key Technical Highlights

* **Single Source of Truth (SSOT) Countdown Stream**:
  * Driven by a background \AppMonitorService\ emitting \StateFlow<Long>\ every 1,000ms.
  * Decoupled Compose overlays strictly observe state rather than running independent, unaligned local timers.
* **Dynamic WindowManager Overlays**:
  * Implements floating countdown pills with gesture-driven repositioning, edge-snapping, and 3-second idle ghost-mode transparency (35% alpha).
  * Uses \ComponentCallbacks\ and Android 11+ \currentWindowMetrics\ to dynamically clamp pill coordinates across screen orientation changes.
* **Restrained Material 3 Design System**:
  * Built on an eye-friendly **Botanical Obsidian (#111413)** and **Mineral Sage (#34D399)** palette adhering to WCAG AAA/AA contrast standards (14.5:1 text contrast).
  * System bar contrast enforcement overrides to eliminate white navigation bar artifacts in edge-to-edge dark mode.
  * Direct \WindowManager\ ComposeView theme injection to maintain unified design tokens across out-of-activity overlays.

---

## 🏗️ Tech Stack

* **Language**: Kotlin 1.9+
* **UI**: Jetpack Compose, Material 3, Edge-to-Edge (\enableEdgeToEdge\)
* **Local Persistence**: Room Database (SQLite), Flow / StateFlow
* **Background Processing**: Android Foreground Service, Coroutines, \UsageStatsManager\
* **Build System**: Gradle Kotlin DSL (\uild.gradle.kts\), Java 17

---

## 🚀 Getting Started

### Prerequisites
* Android Studio Iguana / Jellyfish or newer
* JDK 17 (configured via Gradle)
* Android Device or Emulator running Android 8.0+ (API 26+)

### Permissions Required
1. **Usage Access (\PACKAGE_USAGE_STATS\)**: To detect foreground application changes.
2. **Display Over Other Apps (\SYSTEM_ALERT_WINDOW\)**: To render ambient timers and hard lockouts.
3. *(Optional for Xiaomi/HyperOS)*: Disable Battery Optimization & allow Autostart for uninhibited background tracking.

---

## 📄 License
This project is licensed under the Apache License 2.0.
