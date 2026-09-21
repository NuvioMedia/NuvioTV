package com.nuvio.tv.ui.screens.player.subtitles

// Both names are historical: the enum value is persisted in DataStore and cloud backups, so
// renaming either one breaks restore on other devices. They mean "the Groq model" and "the Gemini
// model" — the actual model ids live in SubtitleTranslationService.
enum class SubtitleAiModel {
    // Now maps to openai/gpt-oss-120b (llama-3.3-70b decommissioned August 2026).
    GROQ_LLAMA_70B,
    // Now maps to gemini-3.5-flash-lite (2.5 retired July 2026). Name kept: the enum value is
    // persisted in DataStore and cloud backups — renaming breaks restore on other devices.
    GEMINI_FLASH_25
}
