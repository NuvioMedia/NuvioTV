/*
 * Copyright (C) 2006 Evgeniy Stepanov <eugeni.stepanov@gmail.com>
 * Copyright (C) 2011 Grigori Goronzy <greg@chown.ath.cx>
 *
 * This file is part of libass.
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

#ifndef LIBASS_TYPES_H
#define LIBASS_TYPES_H

#include <stdint.h>

/**
 * GENERAL NOTE regarding the definitions exposed by this header
 *
 * The main use case for this is _reading_ the track fields, especially
 * track->YCbCrMatrix, to correctly display the rendering results.
 *
 * Furthermore, the exposed definitions also open up the possibility to _modify_
 * the exposed structs, working closer to library internals and bypassing
 * e.g. creation of intermediate ASS-text buffers when creating dynamic events.
 * This is an advanced use case and should only be done when well-versed in ASS
 * and aware of the effects and legal values of _all_ fields of the structs.
 * The burden of sanitising and correctly initialising fields is then also
 * placed on the API user.
 * By nature of direct struct modifications working closer to library internals,
 * workflows that make use of this possibility are also more likely to be
 * affected by future API breaks than those which do not.
 * If modifying a libass struct to contain a pointer to a user-allocated buffer,
 * the buffer should be allocated using ass_malloc(). Failing to do so may result
 * in undefined behavior if the buffer is later freed within libass, and the caller
 * is linked against a different libc instance than libass itself. Similarly, any
 * existing buffer being replaced or removed should be released using ass_free().
 *
 * To avoid desynchronisation with internal states, there are some restrictions
 * on when and how direct struct modification can be performed.
 * Ignoring them may lead to undefined behaviour. See the following listing:
 *
 *  - Manual struct edits and track-modifying (including modification to the
 *    event and style elements of the track) API calls cannot be freely mixed:
 *    - Before manual changes are performed, it is allowed to call any such API,
 *      unless the documentation of the function says otherwise.
 *    - After manual changes have been performed, no track-modifying API may be
 *      invoked, except for ass_track_set_feature and ass_flush_events.
 *  - After the first call to ass_render_frame, existing array members
 *    (e.g. members of events) and non-array track fields (e.g. PlayResX
 *    or event_format) must not be modified. Adding new members to arrays
 *    and updating the corresponding counter remains allowed.
 *  - Adding and removing members to array fields, like events or styles,
 *    must be done through the corresponding API function, e.g. ass_alloc_event.
 *    See the documentation of these functions.
 *  - The memory pointed to by string fields (char *) must be
 *    free'able by the implementation of free used by libass.
 *
 * A non-exhaustive list of examples of track-modifying API functions:
 *   ass_process_data, ass_process_codec_private,
 *   ass_process_chunk, ass_read_styles, ...
 *
 * Direct struct modification can be done safely, but it is also easy to
 * miss an initialisation or violate these restrictions, thus introducing bugs
 * that may not manifest immediately. It should be carefully considered
 * whether this is worthwhile for the desired use-case.
 */

#define VALIGN_SUB 0
#define VALIGN_CENTER 8
#define VALIGN_TOP 4
#define HALIGN_LEFT 1
#define HALIGN_CENTER 2
#define HALIGN_RIGHT 3
#define ASS_JUSTIFY_AUTO 0
#define ASS_JUSTIFY_LEFT 1
#define ASS_JUSTIFY_CENTER 2
#define ASS_JUSTIFY_RIGHT 3

#define FONT_WEIGHT_LIGHT  300
#define FONT_WEIGHT_MEDIUM 400
#define FONT_WEIGHT_BOLD   700
#define FONT_SLANT_NONE    0
#define FONT_SLANT_ITALIC  100
#define FONT_SLANT_OBLIQUE 110
#define FONT_WIDTH_CONDENSED 75
#define FONT_WIDTH_NORMAL    100
#define FONT_WIDTH_EXPANDED  125


/* Opaque objects internally used by libass.  Contents are private. */
typedef struct ass_renderer ASS_Renderer;
typedef struct render_priv ASS_RenderPriv;
typedef struct parser_priv ASS_ParserPriv;
typedef struct ass_library ASS_Library;

/* ASS Style: line */
typedef struct ass_style {
    char *Name;     //must be a valid non-NULL string pointer; may be an empty string
    char *FontName; //must be a valid non-NULL string pointer; may be an empty string
    double FontSize;
    uint32_t PrimaryColour;
    uint32_t SecondaryColour;
    uint32_t OutlineColour;
    uint32_t BackColour;
    int Bold;      // 0 or 1 (boolean)
    int Italic;    // 0 or 1 (boolean)
    int Underline; // 0 or 1 (boolean)
    int StrikeOut; // 0 or 1 (boolean)
    double ScaleX; // positive with 1.0 representing 100%
    double ScaleY; // positive with 1.0 representing 100%
    double Spacing;
    double Angle;
    int BorderStyle;
    double Outline;
    double Shadow;
    int Alignment; // use `VALIGN_* | HALIGN_*` as value
    int MarginL;
    int MarginR;
    int MarginV;
    int Encoding;
    int treat_fontname_as_pattern; // does nothing (left in place for ABI-compatibility)
    double Blur; // sets a default \blur for the event; same values as \blur
    int Justify; // sets text justification independent of event alignment; use ASS_JUSTIFY_*
} ASS_Style;


/*
 * ASS_Event corresponds to a single Dialogue line;
 * text is stored as-is, style overrides will be parsed later.
 */
typedef struct ass_event {
    long long Start;            // ms
    long long Duration;         // ms

    int ReadOrder;
    int Layer;
    int Style;
    char *Name;
    int MarginL;
    int MarginR;
    int MarginV;
    char *Effect;
    char *Text;

    ASS_RenderPriv *render_priv;
} ASS_Event;

/**
 * Support for (xy-)VSFilter mangled colors
 *
 * Generally, xy-VSFilter emulates the classic VSFilter behavior of
 * rendering directly into the (usually YCbCr) video. Classic
 * guliverkli(2)-VSFilter is hardcoded to use BT.601(TV) as target colorspace
 * when converting the subtitle RGB color to the video colorspace.
 * This led to odd results when other colorspaces were used, particular
 * once those became more common with the rise of HDTV video:
 * HDTV typically uses BT.709(TV), but VSFilter continued assuming
 * BT.601(TV) for conversion.
 *
 * This means classic vsfilter will mangle colors as follows:
 *
 *    screen_rgb = video_csp_to_rgb(rgb_to_bt601tv(ass_rgb))
 *
 * where video_csp is the colorspace of the video with which the
 * subtitle was muxed.
 *
 * Subtitle authors worked around this issue by adjusting the color
 * to look as intended *after* going through the mangling process. Still,
 * this behaviour isn't great and also limits the color range. Yet,
 * for backwards compatibility with existing files, the classic mangling
 * must be preserved for existing files to not break the display of
 * color-matched typesets created with older VSFilter versions. Thus,
 * on initiative of xy-VSFilter/XYSubFilter a new explicit "YCbCr Matrix"
 * header was introduced to allow new files to avoid this color mangling.
 * However due to a limitation of VSFilter API, VSFilters don't actually
 * know the real colorspace of the video they're rendering to, so the
 * header wasn't created as a simple "Use ColourMangling: yes/no", but instead
 * specifies exactly which colorspace to use for the initial conversion
 * from the subtitle's RGB values to the video's YCbCr. So we now got
 *
 *    screen_rgb = video_csp_to_rgb(rgb_to_ycbcr_header_csp(ass_rgb))
 *
 * with rgb_to_ycbcr_header_csp defaulting to TV-range BT.601.
 *
 * XySubFilter, whose API was planned during introduction of this header,
 * is not affected by this VSFilter-API limitation, so for it and other
 * renderers like libass an additional special value "None" was also added.
 * "None" tells the renderer to directly use untouched RGB values without
 * any conversion.
 *
 * If the video itself is already in RGB natively, then no color mangling
 * happens regardless of the presence or value of a "YCbCr Matrix" header.
 *
 * The above mangling process with special value "None" to opt out
 * of any color mangling is the recommended default behaviour.
 *
 * Keep in mind though, that xy-VSFilter cannot accurately implement this and
 * will instead resort to a guessing the video colorspace based on resolution
 * and then convert RGB to the guessed space.
 * Also some versions of MPC-HC's Internal Subtitle Renderer don't implement
 * "None" and use TV.601 for unknown, but the video colorspace for no or an
 * empty header (which can break old subtitles).
 *
 * Aegisub's (the main application to produce ASS subtitle scripts) behaviour
 * regarding colorspaces is unfortunately a bit confusing.
 * As of time of writing there still is a config option to force BT.601(TV)
 * in some active forks (which should not be used to author subs and serves
 * at most as a tool to check how now ancient VSFilters would have rendered the
 * subs), the automatically chosen colorspace may depend on the fork and the
 * videoprovider used and furthermore Aegisub likes to override
 * "YCbCr Matrix: None" with the autodetected space of a loaded video.
 * Supposedly some Aegisub versions had an option that "tries not to mangle the
 * colors". It was said that if the header is not set to BT.601(TV), the colors
 * were supposed not to be mangled, even if the header was not set to "None".
 *
 * In general, misinterpreting this header or not using it will lead to
 * slightly different subtitle colors, which can matter if the subtitle
 * attempts to match solid colored areas in the video.
 * It is recommended to stick to XySubFilter-like behaviour described above.
 * A highly motivated application may also expose options to users to  emulate
 * xy-VSFilter's resolution-depended guess or other (historic) mangling modes.
 * Completely ignoring the color mangling is likely to give bad results.
 *
 * Note that libass doesn't change colors based on this header. It
 * absolutely can't do that, because the video colorspace is required
 * in order to handle this as intended. API users must use the exposed
 * information to perform color mangling as described above.
 *
 * Further note all of the above only concerns the RGB values.
 * Color primaries and transfer characteristics of ASS subtitles
 * must always match their associated video when placed on top of SDR video.
 *
 * Subs on HDR video need additional consideration to yield satisfactory results,
 * but unfortunately no mechanisms for this are standardized yet.
 * Until such mechanisms are introduced and sub files start to enable them,
 * all subtitles are to be considered SDR. When placed on top of HDR video
 * exact color matching is not relevant and the choice of SDR colorspace
 * to render subs in is left to the presenter.
 */
typedef enum ASS_YCbCrMatrix {
    YCBCR_DEFAULT = 0,  // Header missing
    YCBCR_UNKNOWN,      // Header could not be parsed correctly
    YCBCR_NONE,         // "None" special value
    YCBCR_BT601_TV,
    YCBCR_BT601_PC,
    YCBCR_BT709_TV,
    YCBCR_BT709_PC,
    YCBCR_SMPTE240M_TV,
    YCBCR_SMPTE240M_PC,
    YCBCR_FCC_TV,
    YCBCR_FCC_PC
    // New enum values can be added here in new ABI-compatible library releases.
} ASS_YCbCrMatrix;

/*
 * ass track represent either an external script or a matroska subtitle stream
 * (no real difference between them); it can be used in rendering after the
 * headers are parsed (i.e. events format line read).
 */
typedef struct ass_track {
    int n_styles;           // amount used
    int max_styles;         // amount allocated
    int n_events;
    int max_events;
    ASS_Style *styles;    // array of styles, max_styles length, n_styles used
    ASS_Event *events;    // the same as styles

    char *style_format;     // style format line (everything after "Format: ")
    char *event_format;     // event format line

    enum {
        TRACK_TYPE_UNKNOWN = 0,
        TRACK_TYPE_ASS,
        TRACK_TYPE_SSA
    } track_type;

    // Script header fields
    int PlayResX;
    int PlayResY;
    double Timer;
    int WrapStyle;
    int ScaledBorderAndShadow; // 0 or 1 (boolean)
    int Kerning; // 0 or 1 (boolean)
    char *Language; // zero-terminated ISO-639-1 code
    ASS_YCbCrMatrix YCbCrMatrix;

    int default_style;      // index of default style
    char *name;             // file name in case of external subs, 0 for streams

    ASS_Library *library;
    ASS_ParserPriv *parser_priv;

    int LayoutResX;  // overrides values from ass_set_storage_size and
    int LayoutResY;  // also takes precedence over ass_set_pixel_aspect

    // New fields can be added here in new ABI-compatible library releases.
} ASS_Track;

#endif /* LIBASS_TYPES_H */
