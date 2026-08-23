package com.romm.desktop.controller.keyboard

import androidx.compose.ui.input.key.Key

data class DesktopKeyboardKey(val scancode: Int, val label: String)

private val keyMap: Map<Key, DesktopKeyboardKey> = buildMap {
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { index, key ->
        put(key, DesktopKeyboardKey(4 + index, ('A' + index).toString()))
    }
    put(Key.One, DesktopKeyboardKey(30, "1"))
    put(Key.Two, DesktopKeyboardKey(31, "2"))
    put(Key.Three, DesktopKeyboardKey(32, "3"))
    put(Key.Four, DesktopKeyboardKey(33, "4"))
    put(Key.Five, DesktopKeyboardKey(34, "5"))
    put(Key.Six, DesktopKeyboardKey(35, "6"))
    put(Key.Seven, DesktopKeyboardKey(36, "7"))
    put(Key.Eight, DesktopKeyboardKey(37, "8"))
    put(Key.Nine, DesktopKeyboardKey(38, "9"))
    put(Key.Zero, DesktopKeyboardKey(39, "0"))
    put(Key.Enter, DesktopKeyboardKey(40, "Enter"))
    put(Key.Backspace, DesktopKeyboardKey(42, "Backspace"))
    put(Key.Tab, DesktopKeyboardKey(43, "Tab"))
    put(Key.Spacebar, DesktopKeyboardKey(44, "Space"))
    put(Key.DirectionRight, DesktopKeyboardKey(79, "Right Arrow"))
    put(Key.DirectionLeft, DesktopKeyboardKey(80, "Left Arrow"))
    put(Key.DirectionDown, DesktopKeyboardKey(81, "Down Arrow"))
    put(Key.DirectionUp, DesktopKeyboardKey(82, "Up Arrow"))
    put(Key.CtrlLeft, DesktopKeyboardKey(224, "Left Ctrl"))
    put(Key.ShiftLeft, DesktopKeyboardKey(225, "Left Shift"))
    put(Key.AltLeft, DesktopKeyboardKey(226, "Left Alt"))
    put(Key.CtrlRight, DesktopKeyboardKey(228, "Right Ctrl"))
    put(Key.ShiftRight, DesktopKeyboardKey(229, "Right Shift"))
    put(Key.AltRight, DesktopKeyboardKey(230, "Right Alt"))
}

fun keyboardKeyFor(key: Key): DesktopKeyboardKey? = keyMap[key]

fun keyboardKeyLabel(scancode: Int?): String =
    if (scancode == null) "Unmapped"
    else keyMap.values.firstOrNull { it.scancode == scancode }?.label ?: "Key $scancode"
