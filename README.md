# ZiG — Zen i Guess

> An on-device Android notification interceptor that classifies, filters, and surfaces the notifications that actually matter — with zero data leaving your phone.

---

## Why ZiG?

Modern smartphones are buried in notification noise: promotional messages, referral spam, social media pings, and marketing blasts that interrupt focus without adding value. Existing solutions either forward your notifications to a cloud service, require a subscription, or lack the precision to distinguish a real bank transaction from a promotional SMS that just mentions a rupee amount.

ZiG takes a different approach: every decision is made entirely on-device, deterministically, with a layered pipeline that prioritises speed and privacy over convenience.

---

## How it works

ZiG registers as a `NotificationListenerService` and intercepts every incoming notification before it reaches you. Each notification is routed through a sequential decision pipeline — cheap deterministic checks first, expensive ML inference last:

```
onNotificationPosted()
  │
  ├─ [pre-flight] Structural filters ──────────────────────── DROP silently
  │   ZiG's own republished notifications, ongoing service
  │   notifications, OS system events, blank notifications
  │
  ├─ Gate 1: Managed app check (Rust / JNI) ──────────────── MANAGED_FAIL → suppress
  │   Only apps you explicitly opt in to are processed.
  │
  ├─ [Locked-screen bypass] ──────────────────────────────── LOCKED_PASS → publish
  │   VISIBILITY_PRIVATE + active keyguard → forward immediately.
  │   Redacted text cannot be meaningfully evaluated; skip ML.
  │
  ├─ Gate 2: Contact whitelist (Rust / JNI) ──────────────── CONTACT_PASS → publish
  │   Sender name matched against trusted contacts.
  │
  ├─ Gate 3: Keyword rules (Rust / JNI) ──────────────────── KEYWORD_PASS → publish
  │   Chained AND-keyword rules (e.g. "cab, arriving").
  │
  └─ On-device LLM (Google LiteRT / Qwen 0.5B 4-bit) ─────── ALLOWED → publish
      Lazy-loaded, TTL-cached, released after 5 min idle.    BLOCKED → suppress
```

Notifications that pass are republished at `IMPORTANCE_HIGH` under ZiG's own channel, ensuring they surface as heads-up banners even during active phone calls.

Every pipeline stage is written to a local Room database, giving you a full per-job trace you can inspect at any time in the Logs tab.

---

## Privacy guarantees

| What | How it is enforced |
|---|---|
| **No internet access** | `android.permission.INTERNET` is explicitly stripped from the merged manifest via `tools:node="remove"` |
| **All data on-device** | No Firebase, no cloud APIs, no external SDK that makes network calls |
| **No analytics or telemetry** | No Sentry, Crashlytics, or any crash/usage reporting |
| **ML stays local** | LiteRT inference runs fully on-device; model weights never leave the device |
| **No data uploads** | Notifications, contacts, embeddings, and model outputs are never transmitted |

---

## Features

### Notification Review
A triage inbox showing every notification ZiG processed. Approve or block with a single tap to train future behaviour. Animated card state transitions make the action feel immediate. Undo is always available.

### Archive
Notifications older than the active review window are moved to an archive view, accessible via a toggle in the top bar.

### Managed Apps
A searchable list of every installed app. Tap to toggle whether ZiG intercepts that app's notifications. Apps you manage appear at the top of the list.

### Custom Rules Vault
Define keyword rules that bypass the LLM entirely. Rules support chained AND conditions — a rule of `"cab, arriving"` matches only notifications whose body contains both words. Rules can be edited in-place and reordered.

### Pipeline Log
A full trace of the last 500 pipeline events, grouped by job ID. Each stage is colour-coded by outcome. Supports grep-style search with `app:`, `status:`, and `msg:` prefixes.

### Locked-Screen Bypass
When the device is locked and a notification is marked `VISIBILITY_PRIVATE`, ZiG skips the ML pipeline and forwards immediately — preventing redacted text from poisoning the model context.

---

## Architecture

```
UI (Jetpack Compose + Material 3)
        ↓
ViewModel (StateFlow + Hilt)
        ↓
    Use Cases
        ↓
    Repository
        ↓
Data Sources
  ├─ Room (SQLite)          — notification log, review queue, keyword rules, managed apps
  ├─ Rust JNI               — managed app gate, contact whitelist, keyword rule engine
  └─ LiteRT (on-device LLM) — final allow/block decision for uncategorised notifications
```

### Module map

```
app/src/main/java/dev/zig/notificationfilter/
├── core/di/                     # Hilt modules (DB, LLM, coroutine scope)
├── data/
│   ├── local/
│   │   ├── db/                  # Room entities, DAOs, migrations (v1→v5)
│   │   ├── NativeBridge.kt      # Kotlin ↔ Rust JNI bridge
│   │   └── ContactsSyncManager.kt
├── domain/
│   ├── llm/                     # LiteRT engine — lazy init, Mutex-guarded, TTL-cached
│   └── NotificationPublisher.kt # Heads-up republisher with channel rotation
├── service/
│   ├── ZigNotificationListenerService.kt  # Pipeline orchestrator
│   └── NotificationActionReceiver.kt      # Dismiss / open-ZiG actions
└── ui/
    ├── review/                  # Notification triage inbox + archive
    ├── apps/                    # Managed apps toggle screen
    ├── rules/                   # Custom Rules Vault
    ├── logs/                    # Pipeline log viewer
    ├── common/                  # ZigEmptyState + Canvas doodles
    └── navigation/              # ZigScreen sealed class + HorizontalPager nav

native/rust_filter/              # Rust JNI library (cdylib)
└── src/lib.rs                   # Thread-safe OnceLock sets for managed apps,
                                 # contact whitelist, and chained keyword rules
```

---

## Tech stack

| Layer | Technology | Version |
|---|---|---|
| Language | Kotlin | 2.0.21 |
| UI | Jetpack Compose + Material 3 | BOM 2024.09.00 |
| Architecture | MVVM + Use Cases | — |
| DI | Hilt | 2.52 |
| Database | Room | 2.6.1 |
| Background work | WorkManager | 2.9.1 |
| Native filter engine | Rust (JNI via `cargo-ndk`) | 2021 edition |
| On-device ML | Google LiteRT (MediaPipe Tasks GenAI) | 0.10.35 |
| Build system | Gradle Kotlin DSL + AGP | 8.5.2 |
| NDK | NDK | 27.0.12077973 |
| Min SDK | Android 8.0 Oreo | API 26 |
| Target SDK | Android 14 | API 34 |

---

## Building from source

### Prerequisites

- **Android Studio** Ladybug (2024.2) or newer
- **NDK** `27.0.12077973` — install via *SDK Manager → SDK Tools → NDK (Side by side)*
- **Rust + Cargo** — install from [rustup.rs](https://rustup.rs)
- **cargo-ndk** — the Gradle build task installs this automatically on first run if absent

### ML model

ZiG uses a quantised Qwen 0.5B 4-bit model (`qwen_250m_4bit.task`) via Google LiteRT. The model file is excluded from the repository (`.task` files are in `.gitignore` due to size).

You must place the model in the assets directory before building:

```
app/src/main/assets/qwen_250m_4bit.task
```

The model can be converted from the Qwen2.5-0.5B-Instruct HuggingFace checkpoint using the [MediaPipe Model Maker](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference) toolchain.

### Build steps

```bash
git clone https://github.com/meguminsan12321/zen-i-guess.git
cd zen-i-guess
./gradlew assembleDebug
```

The `buildRustLib` Gradle task compiles the Rust filter engine for `arm64-v8a` and `x86_64` before every build. No manual `cargo` invocation is required.

---

## Design principles

1. **Privacy first** — No notification content or user data ever leaves the device.
2. **Offline first** — Full functionality with no network connection, ever.
3. **Battery efficient** — Deterministic Rust gates run in microseconds. LLM inference is lazy-loaded and released after 5 minutes idle. No polling.
4. **Deterministic** — Rules Engine always runs before ML. If a deterministic gate fires, the model is never invoked.
5. **Maintainable** — Small functions, immutable data, explicit Room migrations, no singleton abuse.
6. **Explainable** — Every pipeline decision is logged with a reason string, a job ID, and a timestamp.

---

## Roadmap

- [ ] Replace LLM with a custom lightweight on-device TFLite classifier (faster, lower RAM, runs on API 26 devices without MediaPipe dependency)
- [ ] Active learning: surface user Allow/Block decisions as training signal for the on-device model
- [ ] Smart reply suggestions (on-device)
- [ ] Per-app notification grouping in the review inbox
- [ ] Export / import keyword rules

---

## License

[MIT](LICENSE) © 2026 Prithvi P Vasistha
