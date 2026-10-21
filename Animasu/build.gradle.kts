// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    description = "Animasu — tema anime populer (div.bs/div.tt, mobius/mirror loadLinks)."
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
    )

    iconUrl = "https://animasu.love/wp-content/uploads/2023/01/cropped-favicon-32x32.png"
}
