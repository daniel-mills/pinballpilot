# Pinball Pilot

Native Android pinball strategy coach with a **Nuxt 4 / Vue 3 / Vuetify 3 / TypeScript** content workshop and Supabase backend.

This is a development build for the agreed five-person private pilot. It includes a complete **fictional Workshop table** for offline testing. Authentic **Iron Maiden: Legacy of the Beast — Pro** findings are stored as pending research; none have been approved on your behalf. The authentic guide cannot ship until you review its content, supply a licensed reference image, and verify its annotations.

## Run the admin workshop

Use Node **22.22.3** (see `.nvmrc`) or a compatible version in `package.json`.

```powershell
npm ci
npm run dev
```

Open http://localhost:3000. Without Supabase configuration it runs as a clearly labelled browser-local sandbox: review findings, edit shot points/polygons, upload a reference image, inspect/edit structured guides, validate publication, and export versioned JSON snapshots. Sandbox decisions never publish live content. Back up important sandbox work with **Export current draft**; browser storage is not a production database.

For this workspace, `scripts/dev-admin.ps1` automatically uses the local compatible Node installation when present.

```powershell
npm run build
node admin/.output/server/index.mjs
```

## Research using your Codex or Claude subscription

The [MCP connector setup](docs/MCP.md) connects your assistant to stored machine knowledge. Assistants can submit sourced proposals; you review them in Vuetify before incorporating them into a published guide. It requires the connected backend and a scoped connector token from Settings. No AI API key is needed for this research workflow.

## Build the Android app

Requirements: JDK 17, Android SDK platform 36, build-tools 35.0.0, internet for initial dependency/model download. No Android Studio dependency is required for command-line builds. Open `android/` in Android Studio for interactive development.

```powershell
./scripts/setup-voice.ps1
cd android
./gradlew.bat :app:assembleDebug :domain:test :app:lintDebug
```

Set `JAVA_HOME` to JDK 17 and `android/local.properties` to `sdk.dir=C\:/path/to/Android/Sdk` (ignored by Git). `scripts/build-android.ps1` detects the local toolchain installed during initial development. The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. It is a development-signed APK, not a Play Store release.

The bundled demo has twelve annotated shots, simple/advanced scoring and multiball guides, prerequisite-aware recommendations, Room persistence, device TTS, camera capture, account sign-in, private sync and a foreground voice service. “Hey Pinball” uses a local Vosk recognizer; following a wake, Android speech recognition handles an intentional command/question. Wake recognition does not continuously send arcade audio to the AI service. Device speech recognition is governed by the phone's configured provider.

Install an **offline English TTS voice** on the test phone. Hands-free must be enabled from a visible app and requires microphone permission. It pauses when offline. Locked-screen operation, wake accuracy, battery use, interruptions and all three headset/microphone combinations still require actual-device testing. See [hardware acceptance scenarios](docs/HARDWARE-VALIDATION.md).

## Connect Supabase

No cloud project or paid provider has been configured or deployed by this build. Use a Supabase project and the Supabase CLI:

```powershell
supabase start
supabase db reset
supabase functions serve pilot --env-file supabase/.env
```

For a remote project, link the intended project, apply migrations and seed intentionally, set edge secrets, then deploy the function. Follow [backend setup and contracts](docs/BACKEND.md). Do not commit `.env` files or service-role keys.

1. Copy `admin/.env.example` to `admin/.env`; set the Supabase URL and **public anon key**.
2. Configure `supabase/.env` from its example. Service-role and provider credentials remain server-side.
3. Enable email auth and allow redirects for the admin URL and `pinballpilot://auth`. Provision each user's `memberships` row explicitly; signing up alone does not grant pilot or editorial access.
4. Android reads `SUPABASE_URL` and `SUPABASE_ANON_KEY` Gradle properties. Put these public configuration values in `~/.gradle/gradle.properties` or use `-P` build properties. Do not put a service key in the APK.
5. Paid operations are disabled until both database `online_enabled` and verified provider cost bounds are set. Default monthly limit is **A$60**, with A$15 conservatively reserved for infrastructure until actual hosting costs are established. Configure AUD bounds including FX, tax, model input/output, image and web-search costs. Failed/ambiguous calls retain reservations. Never treat an app-side cap as control over unrelated provider usage.

## Tests

```powershell
npm test
npm run typecheck
npx tsc -p tsconfig.backend.json
npm run build
npx playwright install chromium
npx playwright test
```

The browser suite runs the production admin build. For database policy tests, start an isolated PostgreSQL 17 container named `pinballpilot-db-check` and run `scripts/verify-database.ps1`. The script targets an **empty disposable database** and supplies minimal Supabase auth/storage interfaces. It checks migrations, private owner isolation, editor restrictions and budget enforcement; it does not replace staging tests on real Supabase.

Content is generated by `node scripts/generate-content.mjs` and `node scripts/generate-seed.mjs`. These overwrite seed fixtures; do not use them to overwrite editorial work. The actual review workflow stores drafts separately.

## Release status and limits

The agreed MVP is **not yet ready for tester release**. Remaining external gates: cloud credentials/deployment, owner-approved authentic content and image permission, and real phone/machine validation. Live AI, email delivery and cross-device cloud behaviour have not been verified without those services.

Current implementation limits are tracked in [implementation status](docs/STATUS.md), including the structured JSON rules editor, game-grouping corrections and sync conflict behaviour. The A$60 limit is enforced for configured app requests; infrastructure/provider billing still needs reconciliation before a real monetary guarantee.

See [agreed product decisions](docs/PRODUCT.md), [architecture](docs/ARCHITECTURE.md), [content provenance](docs/CONTENT-LICENSES.md) and [weekly machine validation](docs/HARDWARE-VALIDATION.md).
