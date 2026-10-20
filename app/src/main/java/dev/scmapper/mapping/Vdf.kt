package dev.scmapper.mapping

/**
 * Valve KeyValues ("VDF") text format: nested blocks of quoted key/value pairs.
 * Keys may repeat (Steam Input configs have many "group" and "binding" entries), so a node keeps
 * an ordered list of entries.
 */
class VdfNode {
    val entries = ArrayList<Pair<String, Any>>()

    fun add(key: String, value: String) = apply { entries += key to value }
    fun add(key: String, value: VdfNode) = apply { entries += key to value }
    fun node(key: String): VdfNode = VdfNode().also { add(key, it) }

    fun str(key: String): String? = entries.firstOrNull { it.first.equals(key, true) && it.second is String }?.second as String?
    fun child(key: String): VdfNode? = entries.firstOrNull { it.first.equals(key, true) && it.second is VdfNode }?.second as VdfNode?
    fun children(key: String): List<VdfNode> = entries.filter { it.first.equals(key, true) && it.second is VdfNode }.map { it.second as VdfNode }
    fun strings(key: String): List<String> = entries.filter { it.first.equals(key, true) && it.second is String }.map { it.second as String }
}

object Vdf {
    fun parse(text: String): VdfNode {
        val tokens = tokenize(text)
        var i = 0
        fun parseBlock(root: Boolean): VdfNode {
            val node = VdfNode()
            while (i < tokens.size) {
                val t = tokens[i++]
                if (t.brace == '}') {
                    if (root) continue
                    return node
                }
                if (t.brace != null) continue
                val key = t.text
                if (i >= tokens.size) break
                val next = tokens[i++]
                when {
                    next.brace == '{' -> node.add(key, parseBlock(false))
                    next.brace == '}' -> return node
                    else -> node.add(key, next.text)
                }
            }
            return node
        }
        return parseBlock(true)
    }

    private class Token(val text: String, val brace: Char? = null)

    private fun tokenize(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> while (i < s.length && s[i] != '\n') i++
                c == '{' || c == '}' -> { out += Token(c.toString(), c); i++ }
                // Platform conditionals such as [$WIN32] are ignored.
                c == '[' -> {
                    while (i < s.length && s[i] != ']') i++
                    i++
                }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\' && i + 1 < s.length) {
                            i++
                            sb.append(
                                when (s[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    else -> s[i]
                                }
                            )
                        } else {
                            sb.append(s[i])
                        }
                        i++
                    }
                    i++
                    out += Token(sb.toString())
                }
                else -> {
                    val start = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '{' && s[i] != '}' && s[i] != '"') i++
                    out += Token(s.substring(start, i))
                }
            }
        }
        return out
    }

    fun write(root: VdfNode): String = StringBuilder().also { writeNode(it, root, 0) }.toString()

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun writeNode(sb: StringBuilder, node: VdfNode, depth: Int) {
        val indent = "\t".repeat(depth)
        for ((k, v) in node.entries) {
            if (v is VdfNode) {
                sb.append(indent).append(quote(k)).append('\n')
                sb.append(indent).append("{\n")
                writeNode(sb, v, depth + 1)
                sb.append(indent).append("}\n")
            } else {
                sb.append(indent).append(quote(k)).append("\t\t").append(quote(v as String)).append('\n')
            }
        }
    }
}
