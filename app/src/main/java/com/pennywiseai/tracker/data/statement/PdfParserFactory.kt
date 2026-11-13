package com.pennywiseai.tracker.data.statement

object PdfParserFactory {

    private val parsers = listOf(
        // Bank statements with strict, bank-specific markers first: their payee
        // narrations name apps ("PHONEPE…") that the broad app readers key on.
        HdfcBankPdfParser(),
        IdbiBankPdfParser(),
        GPayPdfParser(),
        PhonePePdfParser(),
        PaytmPdfParser(),
        SlicePdfParser(),
        SbiAccountPdfParser(),
        // Card statements before the ICICI savings reader, whose markers are broad.
        HdfcCreditCardPdfParser(),
        IciciCreditCardPdfParser(),
        HsbcCreditCardPdfParser(),
        IciciBankPdfParser()
    )

    fun getParser(extractedText: String): PdfStatementParser? {
        return parsers.firstOrNull { it.canHandle(extractedText) }
    }
}
