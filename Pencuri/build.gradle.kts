// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    description = "PencuriMovie — tema DooPlay (div.ml-item/div.movieplay)."
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

    iconUrl = "https://ww44.pencurimovie.baby/favicon.ico"
}
