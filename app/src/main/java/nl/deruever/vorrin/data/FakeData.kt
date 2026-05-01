package nl.deruever.vorrin.data

object FakeData {
    val books = listOf(
        Audiobook(
            id = "1",
            title = "The Way of Kings",
            author = "Brandon Sanderson",
            uri = "",
            duration = 144_060_000L,
            lastPosition = 574_400L,
            chapters = listOf(
                Chapter(0, "Prologue", 0L, 1_080_000L),
                Chapter(1, "Chapter 1 — Szeth", 1_080_000L, 3_660_000L),
                Chapter(2, "Chapter 2 — Kaladin", 3_660_000L, 7_320_000L),
                Chapter(3, "Chapter 3 — Storm's Approach", 7_320_000L, 10_980_000L),
                Chapter(4, "Chapter 4 — Shallan", 10_980_000L, 14_640_000L),
            )
        ),
        Audiobook(
            id = "2",
            title = "Words of Radiance",
            author = "Brandon Sanderson",
            uri = "",
            duration = 158_400_000L,
            lastPosition = 158_400_000L,
        ),
        Audiobook(
            id = "3",
            title = "Lexie en pneumonoultramicroscopicsilicovolcanoconiosis",
            author = "Bob de Bakker",
            uri = "",
            duration = 175_320_000L,
            lastPosition = 0L,
        ),
    )
}