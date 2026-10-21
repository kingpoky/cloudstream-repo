// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    description = "Enxapi — katalog donghua sub Indo lewat JSON API /api/v1 (bukan scraping HTML)."
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
        "OVA",
    )

    iconUrl = "https://enxapi.id/favicon-32.png"
}
