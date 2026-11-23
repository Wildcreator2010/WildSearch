package com.wildcreator.wildsearch.data.remote

/** Blank clears the saved key; a nonblank key must be a printable ASCII token. */
fun isValidApiKey(key: String): Boolean = key.trim().all { it in '!'..'~' }
