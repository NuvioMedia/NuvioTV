from __future__ import annotations

SPECIAL_RULES = [
    (
        r"upgrade\s+ass-media\s+so\s+Nuvio's\s+custom\s+ASS\s+Matroska\s+extractor\s+"
        r"can\s+register\s+embedded\s+MKV\s+font\s+attachments\s+through\s+"
        r"AssHandler\.addFont\(\.\.\.\)",
        "Improved ASS subtitle font support for embedded Matroska attachments",
    ),
    (
        r"lower\s+debrid\s+cache-sync\s+placeholder\s+threshold\s+to\s+30s",
        "Improved external player auto-next reliability with a faster cache-sync fallback",
    ),
    (
        r"prioritize\s+adaptive\s+extensions\s+and\s+add\s+parsing\s+fallback",
        "Improved player stability with adaptive extension prioritization and parsing fallback",
    ),
    (
        r"fix/rtl\s+layout\s+focus",
        "Fixed RTL focus restoration and keyboard reappearing issues",
    ),
    (
        r"skip\s+redundant\s+enrichment\s+meta\s+requests\s+for\s+source\s+add-on,\s+"
        r"fix\s+Trakt\s+Continue\s+Watching\s+stability,\s+and\s+bunch\s+of\s+other\s+fixes",
        "Improved metadata and add-on behavior while fixing Trakt Continue Watching stability",
    ),
    (
        r"Feat/nuvio\s+exoplayer\s+performance\s+mode\s+clean\s+v2",
        "Added Nuvio Engine player performance settings",
    ),
    (
        r"make\s+connectionPoolSize\s+dynamic\s+and\s+recreate\s+connection",
        "Improved player performance with dynamic connection pool sizing",
    ),
    (
        r"(?:handle\s+)?null\s+response\s+body\s+in\s+fetchManifestText",
        "Fixed empty add-on manifest responses",
    ),
    (
        r"Ac3\s+transcode\s+fix",
        "Improved audio passthrough with AC3 fallback",
    ),
    (
        r"auto-skip\s+each\s+segment\s+only\s+once\s+per\s+playback",
        "Improved auto-skip behavior by skipping each segment only once per playback",
    ),
    (
        r"native\s+memory\s+backward\s+compatibility,\s+HLS\s+freezes\s+fix,\s+"
        r"trailer\s+pool\s+refactor,\s+and\s+default\s+setting\s+updates",
        "Improved ExoPlayer stability with HLS freeze fixes, native memory compatibility, and safer defaults",
    ),
    (
        r"strip\s+DV\s+enabled\s+causing\s+infinite\s+loading\s+for\s+high\s+bitrate\s+"
        r"DV7/HDR10\+\s+files",
        "Fixed infinite loading for high-bitrate Dolby Vision 7 and HDR10+ files when Strip DV is enabled",
    ),
    (
        r"implement\s+non-allocating\s+Dolby\s+Vision\s+RPU\s+conversion\s+and\s+"
        r"optimize\s+JNI\s+memory\s+usage",
        "Improved Dolby Vision conversion and native memory behavior",
    ),
    (
        r"resolve\s+Dolby\s+Vision\s+MKV\s+playback\s+mode\s+issues\s+"
        r"\(always\s+playing\s+as\s+HDR10\s+in\s+Auto/DV8\.1,\s+and\s+always\s+"
        r"playing\s+as\s+DV\s+in\s+HDR\s+baseline\)",
        "Fixed Dolby Vision MKV playback mode selection",
    ),
    (
        r"use\s+AssHandler\.addFont\(name,\s*data\)\s+API\s+directly\s+in\s+the\s+"
        r"Matroska\s+extractor\s+so\s+embedded\s+ASS\s+font\s+attachments\s+are\s+"
        r"registered\s+reliably",
        "Improved embedded ASS subtitle font handling",
    ),
    (
        r"remove\s+subtitle\s+loading\s+causing\s+load\s+animation\s+on\s+titlelogo",
        "Removed subtitle loading behavior that caused title logo animations",
    ),
    (
        r"backport\s+upstream\s+Media3\s+DTS-HD\s+MA\s+extraction\s+logic\s+to\s+custom\s+"
        r"MKV\s+parser",
        "Improved DTS-HD MA passthrough support",
    ),
    (
        r"send\s+resolved\s+intro/outro\s+skip\s+segments\s+to\s+external\s+players",
        "Improved external player support by sending resolved intro and outro skip segments",
    ),
    (
        r"merge\s+skip-intro\s+providers\s+instead\s+of\s+first-match-wins",
        "Improved Skip Intro handling by merging results from multiple providers",
    ),
    (
        r"early-exit\s+next-episode\s+stream\s+search\s+on\s+binge-group/regex\s+match",
        "Improved auto-play by ending next-episode stream searches once a match is found",
    ),
    (
        r"prevent\s+external-player\s+launch\s+stalls\s+after\s+skip\s+merge",
        "Fixed external player launch stalls and fallback regressions",
    ),
    (
        r"remove\s+mistakenly\s+copied\s+lib-exoplayer-release\.aar\s+from\s+root",
        "Removed a mistakenly copied ExoPlayer library from the project root",
    ),
    (
        r"refresh\s+QR\s+auth\s+screen\s+layout",
        "Refreshed the QR authentication screen layout",
    ),
    (
        r"exit\s+app\s+from\s+first\s+launch\s+auth\s+back",
        "Allowed the app to exit when pressing back during first-launch authentication",
    ),
    (
        r"experimental\s+realtime\s+sync",
        "Added experimental real-time sync",
    ),
    (
        r"use\s+nuvio\.tv\s+for\s+TV\s+login\s+QR\s+links",
        "Updated TV login QR links to use nuvio.tv",
    ),
    (
        r"init\s+optional\s+sentry\s+diagnostics",
        "Added optional Sentry diagnostics",
    ),
    (
        r"add\s+diagnostics\s+and\s+use\s+ktor\s+okttp\s+instead\s+of\s+"
        r"supabase\s+sdk\s+okhttp",
        "Added diagnostics and switched networking from the Supabase SDK to Ktor with OkHttp",
    ),
    (
        r"add\s+Sentry\s+and\s+remove\s+QR\s+anonymous\s+auth",
        "Removed anonymous QR authentication",
    ),
    (
        r"nullability\s+issues\s+in\s+response\s+handling\s+(?:in|within)\s+"
        r"AuthManager\s+and\s+"
        r"StreamSpeedTester",
        "Fixed null response handling in authentication and stream speed testing",
    ),
    (
        r"(?:fix\s+)?nullability\s+issues\s+in\s+response\s+handling\s+"
        r"(?:in|within)\s+AuthManager\s+and\s+StreamSpeedTester",
        "Fixed null response handling in authentication and stream speed testing",
    ),
    (
        r"add\s+fallback\s+api\s+url",
        "Added a fallback API URL for improved authentication reliability",
    ),
    (
        r"settings\s+themes",
        "Added selectable themes to the Settings screen",
    ),
    (
        r"improve\s+layout\s+animations",
        "Improved settings layout animations",
    ),
    (
        r"add\s+toggle",
        "Added a real-time sync toggle",
    ),
    (
        r"add\s+m3u\s+and\s+playlist\s+pattern\s+support\s+to\s+MIME\s+type\s+detection",
        "Added `.m3u` and playlist stream detection",
    ),
    (
        r"(?:add\s+)?additional\s+hours",
        "Added more duration options to playback auto-play settings",
    ),
    (
        r"adjust\s+focus\s+handling",
        "Improved settings focus navigation",
    ),
    (
        r"ignore\s+stale\s+watched\s+playback",
        "Improved Trakt reliability by ignoring stale watched playback",
    ),
    (
        r"periodic\s+refresh",
        "Added periodic refresh support",
    ),
    (
        r"sync\s+Trakt\s+credentials\s+across\s+clients",
        "Added Trakt credential syncing across clients",
    ),
    (
        r"locali[sz]ation:\s*Greek\s+translations\s+\(values-el\)",
        "Updated Greek translations",
    ),
    (
        r"adding\s+deeplinks\s+for\s+add-on\s+and\s+detailscreen",
        "Added deep links for add-ons and detail screens",
    ),
    (
        r"login\s+support\s+for\s+self\s+hosted\s+backend",
        "Added login support for self-hosted backends",
    ),
    (
        r"comments\s+card\s+to\s+be\s+in\s+parity\s+with\s+mobile",
        "Updated comments cards to match the mobile experience",
    ),
    (
        r"harden\s+external\s+auto-next\s+loader\s+dismiss,\s+fast\s+fallback,\s+"
        r"and\s+season-less\s+anime",
        "Improved external player auto-next handling with a dismissible loader, fast fallback, and absolute-numbered anime support",
    ),
    (
        r"capture\s+profile\s+ID\s+at\s+event-time\s+to\s+prevent\s+cross-profile\s+"
        r"contamination",
        "Hardened profile isolation by capturing profile IDs at event time",
    ),
    (
        r"collection\s+folder\s+hero\s+videos\s+ignore\s+the\s+\"Play\s+Trailer\s+Muted\"\s+"
        r"setting\s+\(always\s+muted\)",
        "Fixed collection hero videos to respect the trailer mute setting",
    ),
    (
        r"strip\s+in-band\s+P7\s+EL\s+type-63\s+NALs\s+and\s+fix\s+double\s+RPU\s+conversion",
        "Improved Dolby Vision handling with P7 EL NAL stripping and safer RPU conversion",
    ),
    (
        r"Fix/Continue\s+Watching\s+airing\s+series\s+removed\s+on\s+air\s+day",
        "Improved Continue Watching behavior for caught-up series when new episodes air",
    ),
    (
        r"trakt\s+Continue\s+Watching\s+Regression",
        "Fixed Trakt regressions affecting Continue Watching and new episodes",
    ),
    (
        r"revert(?:ed)?\s*:\s*fix\(stream\):\s*prevent\s+loading\s+overlay\s+"
        r"flicker\s+when\s+resolving\s+d…",
        "Reverted a stream loading overlay change that caused debrid/player flicker",
    ),
    (
        r"ASS\s+subtitle\s+gibberish\s+caused\s+by\s+a\s+zlib\s+false-positive\s+"
        r"on\s+plain-text\s+ASS\s+samples\s+in\s+NuvioAssMatroskaExtractor",
        "Fixed ASS subtitle gibberish caused by zlib false positives on plain-text samples",
    ),
    (
        r"(?:resolve\s+)?duplicate\s+preview\s+programs\s+and\s+unit\s+test\s+failures",
        "Fixed duplicate Android TV preview programs",
    ),
    (
        r"update\s+lib-exoplayer-release\.aar\s+with\s+"
        r"DolbyVisionProfileDvheDtb\s+mapping\s+fix",
        "Fixed ExoPlayer Dolby Vision profile mapping",
    ),
    (
        r"recognize\s+Z9X\s+8K\s+model\s+in\s+isZidooDevice\(\)",
        "Added recognition for the Zidoo Z9X 8K device model",
    ),
    (
        r"default\s+to\s+precise\s+add-on\s+release\s+times\s+and\s+fix\s+episode\s+availability",
        "Fixed episode release dates and exact airtime handling",
    ),
    (
        r"shimmer\s+focus\s+loss\s+in\s+collections",
        "Fixed lost shimmer focus in collections",
    ),
    (
        r"grid\s+layout\s+duplicate\s+key\s+crash",
        "Fixed duplicate Grid View and Collection Editor item IDs that could cause crashes",
    ),
    (
        r"settings\s+reset\s+on\s+app\s+update",
        "Added preference backups to recover settings after file corruption",
    ),
    (
        r"polish\s+translations:\s*added\s+missing\s+polish\s+locali[sz]ation\s+strings",
        "Added missing Polish translations",
    ),
    (
        r"invalidate\s+rejected\s+refresh\s+tokens",
        "Fixed rejected Trakt refresh tokens by invalidating unusable credentials",
    ),
    (
        r"prevent\s+stale\s+auto-next\s+replay\s+after\s+profile\s+selection",
        "Prevented stale auto-next playback after profile selection changes",
    ),
    (
        r"correct\s+RTL\s+subtitle\s+punctuation\s+reversal\s+and\s+paren\s+mirroring",
        "Improved RTL subtitle punctuation ordering and mirrored parentheses",
    ),
    (
        r"resolve\s+AFR\s+preflight\s+bypass\s+and\s+restoration\s+regressions",
        "Fixed AFR preflight bypass and display mode restoration regressions",
    ),
    (
        r"set\s+default\s+stock\s+LoadControl\s+back-buffer\s+duration\s+to\s+1\.5s",
        "Adjusted the default player back-buffer duration to 1.5 seconds",
    ),
    (
        r"add\s+IPv4FirstDns\s+to\s+all\s+remaining\s+OkHttpClient\s+instances",
        "Improved network compatibility with IPv4-first DNS handling across remaining network clients",
    ),
    (
        r"fix\s+trailer\s+RTL\s+layout\s+\(progress\s+bar\s+and\s+time\s+left\)",
        "Fixed RTL trailer progress and remaining-time layouts",
    ),
    (
        r"fix\s+all\s+slider\s+settings\s+movement\s+in\s+RTL\s+layout",
        "Fixed RTL playback setting slider controls",
    ),
    (
        r"prevent\s+Continue\s+Watching\s+leak\s+when\s+profiles\s+have\s+different\s+Trakt\s+states",
        "Prevented Continue Watching data from leaking between profiles with different Trakt states",
    ),
    (
        r"add\s+[\"“]?Separate\s+Upcoming\s+Row[\"”]?\s+Continue\s+Watching\s+sort\s+mode",
        "Added an Upcoming Continue Watching row",
    ),
    (
        r"fix\s+vertical\s+fast\s+scroll\s+in\s+classic\s+mode",
        "Fixed Classic mode vertical fast scrolling",
    ),
]
