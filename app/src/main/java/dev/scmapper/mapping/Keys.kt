package dev.scmapper.mapping

/** Names for HID keyboard and consumer usages offered in the action picker. */
object Keys {
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_META = 0x08

    val keyboard: List<Pair<Int, String>> = buildList {
        for (i in 0 until 26) add(4 + i to ('A' + i).toString())
        for (i in 1..9) add(29 + i to i.toString())
        add(39 to "0")
        addAll(
            listOf(
                40 to "Enter", 41 to "Escape", 42 to "Backspace", 43 to "Tab", 44 to "Space",
                45 to "-", 46 to "=", 47 to "[", 48 to "]", 49 to "\\", 51 to ";", 52 to "'", 53 to "`",
                54 to ",", 55 to ".", 56 to "/", 57 to "Caps Lock",
            )
        )
        for (i in 1..12) add(57 + i to "F$i")
        addAll(
            listOf(
                70 to "Print Screen", 71 to "Scroll Lock", 72 to "Pause", 73 to "Insert", 74 to "Home",
                75 to "Page Up", 76 to "Delete", 77 to "End", 78 to "Page Down",
                79 to "Right Arrow", 80 to "Left Arrow", 81 to "Down Arrow", 82 to "Up Arrow",
                101 to "Menu (context)",
                224 to "Left Ctrl", 225 to "Left Shift", 226 to "Left Alt", 227 to "Left Meta",
                228 to "Right Ctrl", 229 to "Right Shift", 230 to "Right Alt", 231 to "Right Meta",
            )
        )
    }

    val media: List<Pair<Int, String>> = listOf(
        0x224 to "Android Back",
        0x223 to "Android Home",
        0x221 to "Search",
        0x040 to "Android Menu",
        0x0E9 to "Volume Up",
        0x0EA to "Volume Down",
        0x0E2 to "Mute",
        0x0CD to "Play / Pause",
        0x0B5 to "Next Track",
        0x0B6 to "Previous Track",
        0x0B7 to "Stop",
        0x06F to "Brightness Up",
        0x070 to "Brightness Down",
    )

    private val keyNames = keyboard.toMap()
    private val mediaNames = media.toMap()

    fun keyName(usage: Int) = keyNames[usage] ?: "Key $usage"
    fun mediaName(usage: Int) = mediaNames[usage] ?: "Media 0x${Integer.toHexString(usage)}"

    fun isModifierKey(usage: Int) = usage in 224..231

    /** Modifier bit (as in the HID report's modifier byte) for a modifier key usage. */
    fun modifierBit(usage: Int) = 1 shl (usage - 224)

    fun modsName(mods: Int): String = buildString {
        if (mods and MOD_CTRL != 0) append("Ctrl+")
        if (mods and MOD_SHIFT != 0) append("Shift+")
        if (mods and MOD_ALT != 0) append("Alt+")
        if (mods and MOD_META != 0) append("Meta+")
    }
}
