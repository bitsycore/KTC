package com.bitsycore.ktc.utils

private const val ANSI_RESET = "\u001B[0m"
private const val ANSI_YELLOW = "\u001B[33m"
private const val ANSI_BOLD_YELLOW = "\u001B[1;33m"

private val ansiEnabled: Boolean = System.console() != null && System.getenv("NO_COLOR") == null

fun String.wrapYellow(): String =
    if (ansiEnabled) "$ANSI_BOLD_YELLOW${this}$ANSI_RESET" else this