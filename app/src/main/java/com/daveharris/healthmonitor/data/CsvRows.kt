package com.daveharris.healthmonitor.data

import java.io.BufferedReader

object CsvRows {
    fun parse(reader: BufferedReader): List<List<String>> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        val rows = mutableListOf<List<String>>()

        fun commitCell() {
            cells += cell.toString()
            cell.clear()
        }

        fun commitRow() {
            if (cells.isEmpty() && cell.isEmpty()) return
            commitCell()
            if (cells.any { it.isNotBlank() }) {
                rows += cells.toList()
            }
            cells.clear()
        }

        var next = reader.read()
        while (next != -1) {
            val char = next.toChar()
            when {
                char == '"' && inQuotes -> {
                    reader.mark(1)
                    val peek = reader.read()
                    if (peek == '"'.code) {
                        cell.append('"')
                    } else {
                        inQuotes = false
                        if (peek != -1) {
                            reader.reset()
                        }
                    }
                }
                char == '"' -> {
                    if (cell.isEmpty()) {
                        inQuotes = true
                    } else {
                        cell.append(char)
                    }
                }
                char == ',' && !inQuotes -> commitCell()
                (char == '\n' || char == '\r') && !inQuotes -> {
                    commitRow()
                    if (char == '\r') {
                        reader.mark(1)
                        val peek = reader.read()
                        if (peek != '\n'.code && peek != -1) {
                            reader.reset()
                        }
                    }
                }
                else -> cell.append(char)
            }
            next = reader.read()
        }
        commitRow()
        return rows
    }
}
