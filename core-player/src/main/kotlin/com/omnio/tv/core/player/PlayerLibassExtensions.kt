package com.omnio.tv.core.player

import com.omnio.tv.data.local.LibassRenderType
import io.github.peerless2012.ass.media.type.AssRenderType

internal fun LibassRenderType.toAssRenderType(): AssRenderType {
    return when (this) {
        LibassRenderType.CUES -> AssRenderType.CUES
        LibassRenderType.EFFECTS_CANVAS -> AssRenderType.EFFECTS_CANVAS
        LibassRenderType.EFFECTS_OPEN_GL -> AssRenderType.EFFECTS_OPEN_GL
        LibassRenderType.OVERLAY_CANVAS -> AssRenderType.OVERLAY_CANVAS
        LibassRenderType.OVERLAY_OPEN_GL -> AssRenderType.OVERLAY_OPEN_GL
    }
}
