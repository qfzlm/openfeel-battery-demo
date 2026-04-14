package com.example.twsbatterydemo.util

class InMemoryLogStore(
    private val capacity: Int = 1000
) {
    private val lock = Any()
    private val lines = ArrayDeque<String>(capacity)

    fun append(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > capacity) {
                lines.removeFirst()
            }
        }
    }

    fun snapshot(): List<String> {
        return synchronized(lock) { lines.toList() }
    }
}
