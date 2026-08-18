package com.gabriel.gamedrop.ui.calendar

internal fun <T> sortByPopularity(
    items: List<T>,
    scoreOf: (T) -> Double,
    nameOf: (T) -> String
): List<T> = items.sortedWith(
    compareByDescending<T> { scoreOf(it) }
        .thenBy { nameOf(it).lowercase() }
)
