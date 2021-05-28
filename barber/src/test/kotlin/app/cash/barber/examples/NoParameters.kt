package app.cash.barber.examples

import app.cash.barber.locale.Locale
import app.cash.barber.models.Document
import app.cash.barber.models.DocumentTemplate

class NoParametersDocument : Document

val noParametersDocumentTemplate = DocumentTemplate(
    fields = mapOf(),
    source = EmptyDocumentData::class,
    targets = setOf(NoParametersDocument::class),
    locale = Locale.EN_US
)
