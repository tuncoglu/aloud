package com.emre.wearbook.books

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The uploader accepts exactly the names this predicate accepts. */
class BooksRepositoryTest {

    @Test
    fun `supported extensions are accepted case-insensitively`() {
        assertTrue(BooksRepository.isSupportedName("book.mp3"))
        assertTrue(BooksRepository.isSupportedName("book.M4B"))
        assertTrue(BooksRepository.isSupportedName("Björn läser.m4b"))
    }

    @Test
    fun `other extensions are rejected`() {
        assertFalse(BooksRepository.isSupportedName("book.mp4"))
        assertFalse(BooksRepository.isSupportedName("book.mp3.part"))
        assertFalse(BooksRepository.isSupportedName("book"))
        assertFalse(BooksRepository.isSupportedName(""))
    }
}
