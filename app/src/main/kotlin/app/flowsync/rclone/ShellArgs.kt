package app.flowsync.rclone

object ShellArgs {
    /** Splits a command line into arguments; understands "double" and 'single' quotes. */
    fun split(s: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = '\u0000'; var has = false
        for (ch in s) {
            when {
                q != '\u0000' -> if (ch == q) q = '\u0000' else cur.append(ch)
                ch == '"' || ch == '\'' -> { q = ch; has = true }
                ch.isWhitespace() -> if (cur.isNotEmpty() || has) { out += cur.toString(); cur.setLength(0); has = false }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty() || has) out += cur.toString()
        return out
    }
}
