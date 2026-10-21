// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    description = "KawanFilm + NodrakorId — family muvipro (KlikxxiEngine, gmr-* classes)."
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
        "Movie",
        "TvSeries",
        "AsianDrama",
    )

    iconUrl = "https://web.kawanfilm21.co/wp-content/uploads/2023/01/cropped-favicon-32x32.png"
}
