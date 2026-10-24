// use an integer for version numbers
version = 4


cloudstream {
    language = "id"
    description = "DramaBox — short drama (katalog + episode gratis, isCharge=0 saja)."
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
        "AsianDrama",
    )

    iconUrl = "https://www.dramabox.com/favicon.ico"
}
