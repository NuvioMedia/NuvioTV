package com.nuvio.tv.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.R

@Composable
fun localizedGenreLabel(genre: String): String {
    val context = LocalContext.current
    return localizedGenreLabel(context, genre)
}

fun localizedGenreLabel(context: Context, genre: String): String = when (genre.lowercase().trim()) {
    "action" -> context.getString(R.string.genre_action)
    "adventure" -> context.getString(R.string.genre_adventure)
    "animation" -> context.getString(R.string.genre_animation)
    "comedy" -> context.getString(R.string.genre_comedy)
    "crime" -> context.getString(R.string.genre_crime)
    "documentary" -> context.getString(R.string.genre_documentary)
    "drama" -> context.getString(R.string.genre_drama)
    "family" -> context.getString(R.string.genre_family)
    "fantasy" -> context.getString(R.string.genre_fantasy)
    "history" -> context.getString(R.string.genre_history)
    "horror" -> context.getString(R.string.genre_horror)
    "music" -> context.getString(R.string.genre_music)
    "mystery" -> context.getString(R.string.genre_mystery)
    "romance" -> context.getString(R.string.genre_romance)
    "science fiction" -> context.getString(R.string.genre_science_fiction)
    "tv movie" -> context.getString(R.string.genre_tv_movie)
    "thriller" -> context.getString(R.string.genre_thriller)
    "war" -> context.getString(R.string.genre_war)
    "western" -> context.getString(R.string.genre_western)
    "action & adventure" -> context.getString(R.string.genre_action_adventure)
    "kids" -> context.getString(R.string.genre_kids)
    "news" -> context.getString(R.string.genre_news)
    "reality" -> context.getString(R.string.genre_reality)
    "sci-fi & fantasy" -> context.getString(R.string.genre_sci_fi_fantasy)
    "soap" -> context.getString(R.string.genre_soap)
    "talk" -> context.getString(R.string.genre_talk)
    "war & politics" -> context.getString(R.string.genre_war_politics)
    else -> genre
}
