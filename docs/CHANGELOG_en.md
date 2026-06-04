# Changelog — Pig Head Assistant

All notable changes are documented here. Follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) format.

## [v1.2] — 2026-06-04

### Added
- **Barge-in modes**: Three switchable modes via settings UI
  - A. Off (default) — pause ASR during TTS, resume after playback
  - B. Voice interrupt — keep ASR active during TTS, interrupt on any speech detected
  - C. Keyword interrupt — keep ASR active during TTS, interrupt only on keyword (default "pig head")
- Settings page: "Barge-in Mode" dropdown selector

### Fixed
- **Status display bug**: State stuck at "replying" after TTS finished; now correctly returns to "listening"
  - Root cause: `startListening()` assigned directly to `state` field, bypassing `updateState()` UI notification
  - Fix: Use `updateState()` for all state transitions

### Changed
- Corrected ROADMAP v1.1 model name (Zipformer → Paraformer)
- Updated tech stack ASR layer to Sherpa-ONNX OnlineRecognizer (Paraformer bilingual int8)

## [v1.1] — 2026-06-03

### Added
- **Bilingual streaming ASR**: OfflineRecognizer → OnlineRecognizer with real-time partial results
  - Model: `sherpa-onnx-streaming-paraformer-bilingual-zh-en` (int8, ~227MB)
  - Mixed CN/EN recognition, terms like "linux kernel", "python API" recognized directly
  - Built-in endpoint detection, auto-completes after speech + silence

### Fixed
- Endpoint false trigger: added `hasSpeech` guard, only fires endpoint when actual speech was detected

### Known Issues
- Zipformer transducer models failed to load due to sherpa-onnx v1.13.2 onnxruntime protobuf compatibility; worked around by switching to Paraformer
- APK size large (~794MB), contains unused CTC and transducer models

## [v1.0] — 2026-06-02

### Added
- 🎤 Sherpa-ONNX Zipformer CTC offline Chinese speech recognition
- 🧠 DeepSeek API streaming LLM conversation
- 🔊 System TTS speech synthesis (Yuemeng engine, 275 voices)
- 🔇 AudioRecord acoustic echo cancellation (AEC)
- ⚡ Adjustable speech rate 0.5x–2.5x
- 📋 Local conversation history with backup/restore
- Jetpack Compose + Material 3 UI
