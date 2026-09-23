package com.localboard.keyboard.engine

/**
 * Compact character trie over the base dictionary.
 * Terminal nodes carry the corpus frequency of the word.
 */
class Trie {

    class Node {
        val children = HashMap<Char, Node>(4)
        var frequency: Long = 0L
            private set

        fun terminal(freq: Long) {
            frequency = freq
        }
    }

    val root = Node()
    var wordCount = 0
        private set

    fun insert(word: String, frequency: Long) {
        var node = root
        for (c in word) {
            node = node.children.getOrPut(c) { Node() }
        }
        if (node.frequency == 0L) wordCount++
        // Max, not overwrite: injected word lists (CONTRACTIONS/TEXTING)
        // re-insert words the corpus already carries and must not lower the
        // base frequency the dictionary established.
        if (frequency > node.frequency) node.terminal(frequency)
    }

    /** Un-learn a word (typo suppression): keeps the path nodes, clears the
     *  terminal frequency so the word no longer counts as known. */
    fun remove(word: String) {
        val n = node(word) ?: return
        if (n.frequency > 0L) {
            wordCount--
            n.terminal(0L)
        }
    }

    fun node(prefix: String): Node? {
        var node = root
        for (c in prefix) {
            node = node.children[c] ?: return null
        }
        return node
    }

    fun frequency(word: String): Long = node(word)?.frequency ?: 0L

    fun contains(word: String): Boolean = frequency(word) > 0L

    /** Every terminal word with its frequency (dictionary-wide enumeration). */
    fun collectAll(): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>(wordCount + 16)
        val sb = StringBuilder(24)
        fun dfs(node: Node) {
            if (node.frequency > 0L) out += sb.toString() to node.frequency
            for ((c, child) in node.children) {
                sb.append(c)
                dfs(child)
                sb.deleteCharAt(sb.length - 1)
            }
        }
        dfs(root)
        return out
    }

    /**
     * Collect up to [cap] words under [prefix], best-first by frequency.
     * [visitBudget] alone bounds the DFS so pathological prefixes stay cheap.
     *
     * There is deliberately NO early exit on result count: children iterate
     * in HashMap order, so aborting once [cap] * N results had piled up made
     * the walk stop before high-frequency branches ("the", "this" under a
     * short prefix) were ever visited — those completions were permanently
     * missing. We now collect everything the budget allows, sort the full
     * set, and take the top [cap]. The tradeoff: the default budget is
     * raised (4000 -> 16000 nodes) so short prefixes reach their best
     * branches, which means a bounded amount of extra walking on huge
     * subtrees; the walk remains capped, so cost stays predictable.
     */
    fun completions(prefix: String, cap: Int, visitBudget: Int = 16_000): List<Pair<String, Long>> {
        val start = node(prefix) ?: return emptyList()
        val results = ArrayList<Pair<String, Long>>(32)
        var visited = 0

        fun dfs(node: Node, sb: StringBuilder) {
            if (visited >= visitBudget) return
            visited++
            if (node.frequency > 0L) {
                results += sb.toString() to node.frequency
            }
            // Visit higher-frequency-ish branches first is overkill; plain DFS is fine
            // because we over-collect and sort afterwards.
            for ((c, child) in node.children) {
                sb.append(c)
                dfs(child, sb)
                sb.deleteCharAt(sb.length - 1)
            }
        }

        dfs(start, StringBuilder(prefix))
        return results.sortedByDescending { it.second }.take(cap)
    }
}
