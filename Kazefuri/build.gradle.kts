// use an integer for version numbers
version = 2


cloudstream {
    language = "id"
    description = "Kazefuri + 2 turunan family (Nekokun, AuraTail) — WP-anime-theme (mobius/mirror)."
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

    iconUrl = "https://kazefuri.space/wp-content/uploads/2023/08/cropped-favicon-32x32.png"
}
