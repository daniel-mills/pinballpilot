# Pinball Pilot — agreed scope

Decisions recorded 9 October 2026 (Australia/Hobart). This supersedes the original brief where they differ.

## Audience and outcome
Five private testers; casual and experienced players, prioritising simple guidance. Used before games and between balls/turns. Players should know the next shot, deliberately start multiball, and feel they are learning the machine. Aim for a complete photo/advice interaction within 30 seconds. No fixed deadline. Initial target: Iron Maiden: Legacy of the Beast **Pro**. Owner has weekly access for photos, videos and validation. Reported test phone: Samsung A27; confirm exact device/Android version during hardware testing.

## Guides and knowledge
Two guides: scoring and multiball. Simple scoring default; advanced strategy optional. Explain why by default. General playing technique is out of scope. Deterministic ranking separates shot difficulty, risk, scoring value and progression. Unknown state produces general advice with uncertainty, never fabricated progress. Every game starts fresh while long-term learning is retained.

Research is part of development and later can be triggered from the admin portal. Store findings and citations. All source types allowed; favour manufacturer sources and flag contradictions, edition and software differences. The owner reviews **each fact and recommendation with sources** in the web portal. No automatic approval. Live AI may derive suggestions from approved facts if clearly labelled; permanent guides require review. Never invent rules. Real content awaiting review must not appear as authentic player guidance. Clearly labelled fictional demonstration packs are permitted.

## Photos and voice
Photo machine identification AND game-state assistance are in release one (overrides the original deferral). May request separate display/playfield photos; allow confirmation of missing information. No live camera tracking, automatic ball detection or AR.

Automatic spoken advice, usually 5–10 seconds with a reason. Tap-to-talk AND hands-free wake phrase (initial phrase: “Hey Pinball”) required. Must work with screen locked after the player enables a session. Bluetooth, wired headsets and phone microphone + headphone output supported. Quiet waiting is default; optional progress prompts. No progress is inferred from silence. If state is unclear give general advice. Offline: downloaded guides, touch controls and device speech output; online voice recognition and AI require connectivity.

## Accounts, games and privacy
Email sign-in links initially. Save favourites, guide/learning progress and games with scores/photos/advice across devices. Automatically group activity, with manual corrections; initially single-player games. Photos and conversations private to the tester; editors cannot browse them. Account/history sync may become premium later; no billing implementation now.

## Operations
Adjustable A$60/month **hard cap across hosting and AI**. All billable requests reserve conservative AUD amounts atomically before execution; include infrastructure allowance and foreign exchange margin. Provider-side limits and reconciliation are required for a real monetary guarantee. Stop online AI when cap is reached, retain offline guides. Keep credentials and privileged writes on server. Nuxt 4 + Vue 3 + Vuetify 3 + TypeScript admin is required.

## Release gates
- Approved Iron Maiden Pro facts AND recommendations; licensed reference image; at least ten verified annotations.
- Accurate two-guide experience; simple + advanced scoring; offline operation and automatic narration.
- Private email accounts and cross-device history with tested isolation.
- Real photo identification/state assistance within the 30-second interaction target, including uncertainty.
- Hands-free wake phrase while locked, verified on actual phone with all audio routes in arcade noise.
- Full cost accounting and cap enforcement; no unbounded paid provider requests.

A running demonstration is a development milestone, not completion of these release gates.
