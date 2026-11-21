package com.vivenotes.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class TableWithoutColumnsTest {

    @Test fun roomsOwnDefinitionLosesOnlyTheNamedColumns() {
        val room = "CREATE TABLE `notebooks` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `closedAt` INTEGER, " +
            "`cloudOnlyAt` INTEGER, `sortIndex` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
        assertEquals(
            "CREATE TABLE `next` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`sortIndex` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            tableWithoutColumns(room, "next", setOf("closedAt", "cloudOnlyAt")),
        )
    }

    /** Wherever SQLite put a column `ALTER TABLE … ADD COLUMN` added, it is found by its name. */
    @Test fun aColumnAddedAfterTheConstraintsIsFoundToo() {
        val altered = "CREATE TABLE `attachments` (`id` TEXT NOT NULL, `refCount` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`), releasedAt INTEGER)"
        assertEquals(
            "CREATE TABLE `next` (`id` TEXT NOT NULL, `refCount` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            tableWithoutColumns(altered, "next", setOf("releasedAt")),
        )
    }

    @Test fun commasAndParenthesesInsideQuotesExpressionsAndCommentsAreNotStructure() {
        val tricky = "CREATE TABLE t (a TEXT DEFAULT 'x, y)', \"b,c\" INTEGER CHECK (b > 0 AND (b < 10)), " +
            "gone BLOB, d TEXT REFERENCES p(x, y) /* note, here */, e -- trailing, comment\n TEXT)"
        assertEquals(
            "CREATE TABLE `next` (a TEXT DEFAULT 'x, y)', \"b,c\" INTEGER CHECK (b > 0 AND (b < 10)), " +
                "d TEXT REFERENCES p(x, y) /* note, here */, e -- trailing, comment\n TEXT)",
            tableWithoutColumns(tricky, "next", setOf("gone")),
        )
    }

    @Test fun namesCompareWithoutCaseHoweverTheyAreQuoted() {
        assertEquals(
            "CREATE TABLE `next` (keep TEXT)",
            tableWithoutColumns(
                "CREATE TABLE t ([Mixed] INTEGER, `Other` TEXT, \"third\" TEXT, keep TEXT)",
                "next",
                setOf("mixed", "OTHER", "Third"),
            ),
        )
    }

    @Test fun aQuotedKeywordIsAColumnAndABareOneStartsAConstraint() {
        assertEquals(
            "CREATE TABLE `next` (id TEXT, PRIMARY KEY(id))",
            tableWithoutColumns("CREATE TABLE t (`primary` TEXT, id TEXT, PRIMARY KEY(id))", "next", setOf("primary")),
        )
    }

    @Test fun whatFollowsTheColumnListIsKept() {
        assertEquals(
            "CREATE TABLE `next` (id TEXT PRIMARY KEY) WITHOUT ROWID",
            tableWithoutColumns("CREATE TABLE t (id TEXT PRIMARY KEY, gone TEXT) WITHOUT ROWID", "next", setOf("gone")),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun aColumnThatIsNotThereIsAnError() {
        tableWithoutColumns("CREATE TABLE t (id TEXT)", "next", setOf("gone"))
    }
}
