package com.nuvio.tv.core.image

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Size

/**
 * Clamp delle decodifiche immagine — la scelta di Prime Video: card compatte
 * a decode fissa. Su una Stick da 1 GB ogni bitmap decodificata a pieno
 * formato durante lo scroll veloce è benzina per l'LMK (kill del 14/10 ore
 * 14:17: RSS 438 MB durante scroll, processo totale 572 MB).
 * Le card mostrano al massimo ~400 px: decodere con lato lungo ≤
 * [MAX_DECODE_PX] è indistinguibile a schermo e taglia la corsa RSS di ~4x.
 *
 * Eccezione: le superfici a schermo intero (hero, sfondo della detail) sono
 * richieste a ~1920 px e venivano rimpicciolite a 960 → rimbalzavano a video
 * al 2x ed erano visibilmente sfocate. Ora arrivano al limite della sorgente
 * TMDB ([FULLSCREEN_MAX_PX] = w1280): ~3,7 MB invece di ~2 MB per un solo
 * sfondo, che è l'unica bitmap grande che resta in memoria.
 *
 * Registrato come interceptor globale in NuvioApplication.newImageLoader:
 * copre poster, sfondi, loghi e miniature senza toccare le chiamate.
 *
 * API Coil 3.3.0: `chain.size` è la dimensione già risolta della richiesta;
 * `withSize()` produce una catena con la dimensione sostituita.
 */
class ClampDecodeInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val size = chain.size
        val widthPx = (size.width as? Dimension.Pixels)?.px
        val heightPx = (size.height as? Dimension.Pixels)?.px
        val longSide = maxOf(widthPx ?: 0, heightPx ?: 0)
        val cap = if (longSide > FULLSCREEN_MIN_PX) FULLSCREEN_MAX_PX else MAX_DECODE_PX
        val cappedWidth =
            if (widthPx != null && widthPx > cap) Dimension.Pixels(cap) else size.width
        val cappedHeight =
            if (heightPx != null && heightPx > cap) Dimension.Pixels(cap) else size.height
        if (cappedWidth == size.width && cappedHeight == size.height) {
            return chain.proceed()
        }
        return chain.withSize(Size(cappedWidth, cappedHeight)).proceed()
    }

    companion object {
        /** Limite per le card (~400 px a schermo): resta invariato. */
        const val MAX_DECODE_PX = 960

        /** Richieste più larghe di così sono superfici a schermo intero. */
        const val FULLSCREEN_MIN_PX = 1280

        /** Decode massima per gli sfondi a tutta pagina (= sorgente w1280). */
        const val FULLSCREEN_MAX_PX = 1280
    }
}
