<div align="center">
  <img src="app/src/main/res/drawable-nodpi/zig_logo.png" alt="ZiG logo" width="160" height="160" />

  <h1>ZiG — Zen i Guess</h1>

  <p><strong>A privacy-focused Android notification interceptor that classifies, filters, and surfaces the notifications that actually matter — entirely on-device, with nothing ever leaving your phone.</strong></p>

  <p>
    <img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" alt="Platform: Android" />
    <img src="https://img.shields.io/badge/min%20SDK-26-3DDC84" alt="Min SDK 26" />
    <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.0.21" />
    <img src="https://img.shields.io/badge/Rust-JNI-CE422B?logo=rust&logoColor=white" alt="Rust JNI" />
    <img src="https://img.shields.io/badge/internet-0%20access-critical" alt="No internet access" />
    <img src="https://img.shields.io/badge/license-MIT-blue" alt="License: MIT" />
  </p>
</div>

---

## Why ZiG?

Modern phones drown you in noise: promotions, referral spam, social pings, and marketing blasts that interrupt without adding value. Most "smart" filters solve this by forwarding your notifications to a cloud service for analysis — trading your privacy for convenience.

ZiG refuses that trade. Every decision is made **locally on your device**, through a layered pipeline that runs cheap deterministic checks first and only escalates to on-device machine learning when nothing simpler can decide. No account, no server, no network — the app cannot even reach the internet.

---

## Privacy guarantees

| What | How it is enforced |
|---|---|
| **No internet access** | `android.permission.INTERNET` is explicitly stripped from the merged manifest via `tools:node="remove"`. The app is structurally incapable of network I/O. |
| **All data on-device** | No Firebase, no cloud APIs, no third-party SDK that makes network calls. |
| **No analytics or telemetry** | No crash reporting, no usage tracking, no ads. |
| **ML stays local** | Both the classifier and the embedding model run fully on-device; weights never leave the phone. |
| **No uploads, ever** | Notifications, contacts, embeddings, and model outputs are never transmitted. |

---

## How it works

ZiG registers as a `NotificationListenerService` and inspects every incoming notification **before** it reaches you. Each one flows through a sequential decision pipeline — the cheapest, most certain checks run first, and the notification exits the moment any layer reaches a confident verdict:

```
onNotificationPosted()
  │
  ├─ Pre-flight structural filters ───────────────────────── DROP silently
  │   ZiG's own re-published notifications, ongoing/service
  │   notifications, OS system events, and blank notifications
  │   are discarded before the tracked pipeline begins.
  │
  ├─ Layer 1 · Managed-app gate (Rust / JNI) ─────────────── not managed → suppress
  │   Only apps you explicitly opt in to are processed.
  │   Everything else passes through ZiG untouched.
  │
  ├─ Sensitive-while-locked handler ──────────────────────── depends on setting
  │   A notification the sender marked private (VISIBILITY_PRIVATE)
  │   arriving while the phone is locked. The "Sensitive
  │   notifications" setting decides:
  │     • ON  (default) → shown immediately, unfiltered.
  │     • OFF           → held silently, then re-fetched with full
  │                        content and classified the moment you unlock.
  │
  ├─ Layer 2 · Contact whitelist (Rust / JNI) ────────────── sender is a contact → publish
  │   The sender is matched against your contacts, kept in
  │   sync in real time (see below).
  │
  ├─ Layer 3 · Keyword rules (Rust / JNI) ────────────────── keyword hit → publish
  │   Deterministic AND-chained keyword rules you define
  │   (e.g. "OTP", "verification", "cab, arriving").
  │
  └─ Layer 4 · On-device ML ensemble ─────────────────────── ALLOW → publish · BLOCK → suppress
      When all deterministic layers miss, a Retrieval-
      Augmented Classifier decides (see below).
```

Notifications that pass are re-published under ZiG's own high-importance channel so they surface as heads-up banners. Every stage of every decision is written to a local database, giving a full, inspectable trace for each notification.

### Real-time contact sync (Rust)

Contact matching is backed by a native **Rust engine** exposed to Kotlin over JNI. On first launch ZiG loads your contact display names into a thread-safe in-memory set held by the Rust library, then registers a `ContentObserver` on the system contacts provider. Whenever the OS signals a change on that channel, ZiG re-syncs the set immediately — so adding, renaming, or removing a contact is reflected in filtering decisions in real time, with no polling and no database round-trip. The same Rust library holds the managed-app set and the AND-chained keyword rules, all guarded by read-write locks for concurrent lookups.

### Layer 4 — the on-device ML ensemble

When a notification survives every deterministic layer, ZiG hands it to a **Retrieval-Augmented Classification (RAC)** ensemble that combines two independent signals:

1. **Base classifier ("Base Instinct").** A custom TFLite text classifier trained on a purpose-built dataset of allow/block-labelled notifications. Text is tokenised with the same Keras vocabulary the model was trained against and reduced to a single `P(BLOCK)` score. The model is memory-mapped from the APK, lazily initialised once, and serialised behind a mutex.

2. **Personal Memory (embeddings + KNN).** Every time you manually override a decision, ZiG embeds that notification with an on-device Universal Sentence Encoder (MediaPipe Text Embedder, L2-normalised) and stores the vector locally. At classification time it runs a **K-Nearest-Neighbours** search (cosine similarity) over this personal corpus and lets your own history *veto* the base model — but only under strict guards: at least a minimum number of neighbours must be retrieved, the closest must clear a high-similarity threshold, and the winning label must hold a strong similarity-weighted consensus. This lets ZiG adapt to *you* without letting a sparse or ambiguous history destabilise the trained model.

If embedding is unavailable or Personal Memory is too sparse to be confident, the ensemble **fails open** to the base model; if the base model itself is missing, it fails open to *allow* and records the error — a notification is never silently lost to an infrastructure fault.

---

## Features

- **Notification Review** — a triage inbox of everything ZiG processed. Approve or block with a single tap; each override feeds Personal Memory so future decisions learn from yours. Undo is always available.
- **Archive** — older notifications move to an archive view, reachable from a toggle in the top bar, so you can revisit or correct past decisions.
- **Managed Apps** — a searchable list of installed apps; tap to choose exactly which apps ZiG intercepts. Unmanaged apps are never touched.
- **Custom Rules** — keyword rules that bypass the ML ensemble entirely. Rules support AND-chained conditions (a `"cab, arriving"` rule matches only when both words appear) and can be edited in place.
- **Daily Summary** — an optional once-a-day digest of what was filtered, scheduled via WorkManager and delivered as a local notification.
- **Sensitive notifications** *(setting, default on)* — governs how sender-private (`VISIBILITY_PRIVATE`) notifications that arrive while the phone is **locked** are treated. **On**, they're shown immediately (some users never want to miss a sensitive alert). **Off**, they're held silently and classified on unlock — because many spam SMS carry the same sensitivity flag, and the lock screen only exposes redacted "sensitive content" text, ZiG waits until unlock, **re-fetches the full content**, and runs it through the normal pipeline so genuine ones surface and spam is suppressed.
- **Replay tour** — the guided onboarding tour can be re-run any time from the Settings menu.
- **Pipeline Log** *(developer surface)* — a full per-notification trace of every pipeline stage and the reason behind each verdict, used for debugging. Hidden from the standard navigation.

---

## Architecture

ZiG follows a unidirectional MVVM + use-case architecture:

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
  ├─ Room (SQLite)          — notification log, review queue, keyword rules,
  │                           managed apps, and Personal Memory vectors
  ├─ Rust / JNI             — managed-app gate, contact whitelist, keyword engine
  ├─ TFLite classifier      — base allow/block verdict ("Base Instinct")
  └─ MediaPipe Text Embedder— sentence embeddings for KNN Personal Memory
```

### Module map

```
app/src/main/java/dev/zig/notificationfilter/
├── core/di/                     # Hilt modules (database, engines, coroutine scope)
├── data/
│   ├── local/
│   │   ├── db/                  # Room entities, DAOs, explicit migrations
│   │   ├── NativeBridge.kt      # Kotlin ↔ Rust JNI bridge
│   │   └── ContactsSyncManager.kt  # real-time contacts → Rust whitelist
│   ├── preferences/             # user settings
│   └── repository/              # repository implementations
├── domain/
│   ├── classifier/              # EnsembleClassifier + base TFLite engine
│   ├── embedding/               # MediaPipe Universal Sentence Encoder wrapper
│   ├── memory/                  # Personal Memory corpus + KNN vector search
│   ├── summary/                 # daily-summary worker + scheduler
│   ├── llm/                     # archived LLM lane (kept for reference)
│   └── NotificationPublisher.kt # heads-up re-publisher
├── service/
│   └── ZigNotificationListenerService.kt  # the pipeline orchestrator
└── ui/                          # Compose screens (review, apps, rules, logs,
                                 # onboarding, tour, navigation, theme)

native/rust_filter/              # Rust JNI library (cdylib)
└── src/lib.rs                   # thread-safe managed-app / contact / keyword sets
```

---

## Tech stack

| Layer | Technology | Version |
|---|---|---|
| Language | Kotlin | 2.0.21 |
| UI | Jetpack Compose + Material 3 | BOM 2024.09.00 |
| Architecture | MVVM + Use Cases | — |
| Dependency injection | Hilt | 2.52 |
| Database | Room | 2.6.1 |
| Navigation | Navigation Compose | 2.8.3 |
| Background work | WorkManager | 2.9.1 |
| Native filter engine | Rust (JNI via `cargo-ndk`) | 2021 edition |
| Base classifier | Custom TFLite via Google LiteRT | 1.0.1 |
| Text embeddings | MediaPipe Text Embedder (Universal Sentence Encoder) | 0.10.35 |
| Build system | Gradle Kotlin DSL + AGP | 8.5.2 |
| NDK | NDK | 27.0.12077973 |
| Min SDK | Android 8.0 Oreo | API 26 |
| Target SDK | Android 14 | API 34 |

---

## Building from source

### Prerequisites

- **Android Studio** Ladybug (2024.2) or newer
- **Android NDK** `27.0.12077973` — install via *SDK Manager → SDK Tools → NDK (Side by side)*
- **Rust + Cargo** — install from [rustup.rs](https://rustup.rs)
- **cargo-ndk** — the Gradle build installs this automatically on first run if it is absent

### Build steps

```bash
git clone https://github.com/prithvi-vasistha/zen-i-guess.git
cd zen-i-guess
./gradlew assembleDebug
```

The on-device models (`zig_classifier.tflite`, `text_embedder.tflite`, and the classifier `vocab.json`) ship in the repository under `app/src/main/assets/`, so no model sideloading is required. The `buildRustLib` Gradle task cross-compiles the Rust filter engine for `arm64-v8a` and `x86_64` before every build — no manual `cargo` invocation is needed.

The resulting APK is written to `app/build/outputs/apk/debug/`. Install it on a device, grant ZiG **Notification Access** and **Contacts** permission when prompted, and choose which apps it should manage.

---

## Design principles

1. **Privacy first** — no notification content or user data ever leaves the device.
2. **Offline first** — full functionality with no network connection, ever.
3. **Battery efficient** — deterministic Rust gates run in microseconds; the ML models are lazy-loaded and reused, never polled.
4. **Deterministic** — the rule layers always run before ML. If a deterministic layer fires, the models are never invoked.
5. **Maintainable** — small functions, immutable data, explicit Room migrations.
6. **Explainable** — every decision is logged with a reason, a job ID, and a timestamp.

---

## License

[MIT](LICENSE) © 2026 Prithvi P Vasistha
