package com.emre.wearbook.books

import android.content.Context
import java.io.File

/** One audiobook file in the library. [id] is the stable file name (media id). */
data class Book(
    val id: String,
    val title: String,
    val file: File,
)

/**
 * Scans `filesDir/books/` for playable files. Uploads land here as
 * `<name>.part` while in progress and are renamed on completion, so
 * the scan never sees half-written files.
 */
object BooksRepository {

    const val BOOKS_DIR = "books"
    private val EXTENSIONS = setOf("mp3", "m4b")

    fun booksDir(context: Context): File =
        File(context.filesDir, BOOKS_DIR).apply { mkdirs() }

    fun list(context: Context): List<Book> =
        booksDir(context).listFiles { f -> f.isFile && !f.name.endsWith(".part") }
            .orEmpty()
            .filter { it.extension.lowercase() in EXTENSIONS }
            .map { Book(id = it.name, title = it.nameWithoutExtension, file = it) }
            .sortedBy { it.title.lowercase() }

    fun bookByName(context: Context, id: String): Book? =
        list(context).firstOrNull { it.id == id }
}
