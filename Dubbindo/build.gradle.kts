// use an integer for version numbers
version = 2


cloudstream {
    language = "id"
    description = "Dubbindo — video self-hosted (video source[src] langsung, bukan iframe embed)."
    authors = listOf("kingpoky")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "Cartoon",
    )

    iconUrl = "https://www.dubbindo.site/favicon.ico"
}
