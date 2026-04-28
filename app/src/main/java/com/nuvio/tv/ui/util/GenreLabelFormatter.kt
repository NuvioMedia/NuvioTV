package com.nuvio.tv.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun localizedGenreLabel(genre: String): String = when (genre.lowercase().trim()) {
    "action" -> stringResource(R.string.genre_action)
    "adventure" -> stringResource(R.string.genre_adventure)
    "animation" -> stringResource(R.string.genre_animation)
    "comedy" -> stringResource(R.string.genre_comedy)
    "crime" -> stringResource(R.string.genre_crime)
    "documentary" -> stringResource(R.string.genre_documentary)
    "drama" -> stringResource(R.string.genre_drama)
    "family" -> stringResource(R.string.genre_family)
    "fantasy" -> stringResource(R.string.genre_fantasy)
    "history" -> stringResource(R.string.genre_history)
    "horror" -> stringResource(R.string.genre_horror)
    "music" -> stringResource(R.string.genre_music)
    "mystery" -> stringResource(R.string.genre_mystery)
    "romance" -> stringResource(R.string.genre_romance)
    "science fiction" -> stringResource(R.string.genre_science_fiction)
    "tv movie" -> stringResource(R.string.genre_tv_movie)
    "thriller" -> stringResource(R.string.genre_thriller)
    "war" -> stringResource(R.string.genre_war)
    "western" -> stringResource(R.string.genre_western)
    "action & adventure" -> stringResource(R.string.genre_action_adventure)
    "kids" -> stringResource(R.string.genre_kids)
    "news" -> stringResource(R.string.genre_news)
    "reality" -> stringResource(R.string.genre_reality)
    "sci-fi & fantasy" -> stringResource(R.string.genre_sci_fi_fantasy)
    "soap" -> stringResource(R.string.genre_soap)
    "talk" -> stringResource(R.string.genre_talk)
    "war & politics" -> stringResource(R.string.genre_war_politics)
    else -> genre
}
