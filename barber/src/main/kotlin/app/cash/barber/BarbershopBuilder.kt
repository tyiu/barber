package app.cash.barber

import app.cash.barber.models.BarberKey
import app.cash.barber.models.CompiledDocumentTemplate
import app.cash.barber.models.Document
import app.cash.barber.models.DocumentData
import app.cash.barber.models.DocumentTemplate
import app.cash.barber.models.Locale
import com.github.mustachejava.DefaultMustacheFactory
import com.google.common.collect.HashBasedTable
import com.google.common.collect.Table
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

class BarbershopBuilder : Barbershop.Builder {
  private val installedDocumentTemplates: Table<KClass<out DocumentData>, Locale, CompiledDocumentTemplate> =
    HashBasedTable.create()
  private val installedDocument: MutableSet<KClass<out Document>> = mutableSetOf()
  private val mustacheFactory = DefaultMustacheFactory()
  private var localeResolver: LocaleResolver = MatchOrFirstLocaleResolver()

  override fun installDocumentTemplate(
    documentDataClass: KClass<out DocumentData>,
    documentTemplate: DocumentTemplate
  ) = apply {
    if (installedDocumentTemplates.contains(documentDataClass, documentTemplate.locale)) {
      throw BarberException(problems = listOf("""
        |Attempted to install DocumentTemplate that will overwrite an already installed DocumentTemplate with locale
        |${documentTemplate.locale}.
        |Already Installed
        |DocumentData: $documentDataClass
        |DocumentTemplate: ${installedDocumentTemplates.row(documentDataClass)}
        |
        |Attempted to Install
        |$documentTemplate
        """.trimMargin()))
    }
    installedDocumentTemplates.put(documentDataClass, documentTemplate.locale,
      documentTemplate.compile(mustacheFactory))
  }

  inline fun <reified DD : DocumentData> installDocumentTemplate(documentTemplate: DocumentTemplate) = installDocumentTemplate(
    DD::class, documentTemplate)

  override fun installDocument(document: KClass<out Document>) = apply {
    installedDocument.add(document)
  }

  inline fun <reified D : Document> installDocument() = installDocument(D::class)

  override fun setLocaleResolver(resolver: LocaleResolver): Barbershop.Builder = apply {
    localeResolver = resolver
  }

  override fun build(): Barbershop = installedDocumentTemplates.validate().asBarbershop()

  /**
   * Validates BarbershopBuilder inputs and returns a Barbershop instance with the installed and validated elements
   */
  private fun Table<KClass<out DocumentData>, Locale, CompiledDocumentTemplate>.validate() = apply {
    val problems: MutableList<String> = mutableListOf()

    cellSet().forEach { cell ->
      val documentDataClass = cell.rowKey!!
      val documentTemplate = cell.value!!

      // DocumentTemplate must be installed with a DocumentData that is listed in its Source
      if (documentDataClass != documentTemplate.source) {
        problems.add("""
          |Attempted to install DocumentTemplate with a DocumentData not specified in the DocumentTemplate source.
          |DocumentTemplate.source: ${documentTemplate.source}
          |DocumentData: $documentDataClass
          """.trimMargin())
      }

      // Documents listed in DocumentTemplate.Targets must be installed
      val notInstalledDocument = documentTemplate.targets.filter {
        !installedDocument.contains(it)
      }
      if (notInstalledDocument.isNotEmpty()) {
        problems.add("""
          |Attempted to install DocumentTemplate without the corresponding Document being installed.
          |Not installed DocumentTemplate.targets:
          |$notInstalledDocument
          """.trimMargin())
      }

      // DocumentTemplates must only use variables from source DocumentData in their fields
      val documentDataConstructor = documentDataClass.primaryConstructor!!
      val documentDataParameterNames = documentDataConstructor.asParameterNames()
      documentTemplate.fields.asFieldCodesMap().forEach { (name, codes) ->
        // Check for missing variables in field templates
        codes.forEach { code ->
          if (!documentDataParameterNames.contains(code.rootKey())) {
            problems.add(
              "Missing variable [$code] in DocumentData [$documentDataClass] for DocumentTemplate field [${documentTemplate.fields[name]?.name}]")
          }
        }
      }

      // Document targets must have primaryConstructor
      // and installedDocumentTemplates must be able to fulfill Document target parameter requirements
      documentTemplate.targets.forEach { documentClass ->
        // Validate that Document has a Primary Constructor
        val documentConstructor = documentClass.primaryConstructor
        if (documentConstructor == null) {
          problems.add("No primary constructor for Document class ${documentClass::class}.")
        } else {
          // Determine non-nullable required parameters
          val requiredParameterNames = documentConstructor.parameters.filter {
            !it.type.isMarkedNullable
          }.map { it.name }

          // Confirm that required field keys are present in installedDocumentTemplates
          if (!documentTemplate.fields.keys.containsAll(requiredParameterNames)) {
            val missingFields = requiredParameterNames.filter {
              !documentTemplate.fields.containsKey(it)
            }
            problems.add("""
              |Installed DocumentTemplate lacks the required non-null fields for Document target
              |Missing fields: $missingFields
              |Document target: ${documentClass::class}
              |DocumentTemplate: $documentTemplate
              """.trimMargin())
          }
        }

        // Lookup installed DocumentTemplates that corresponds to DocumentData
        val documentTemplates = row(documentDataClass)

        if (documentTemplates.isEmpty()) {
          problems.add("""
            |Attempting to build Barber<$documentDataClass, $documentClass>.
            |No installed DocumentTemplates for DocumentData key: $documentDataClass.
            |Check usage of BarbershopBuilder to ensure that all DocumentTemplates are installed to prevent dangling DocumentData.
            """.trimMargin()
          )
        }

        // Confirm that output Document is a valid target for the DocumentTemplate
        documentTemplates.forEach { (_, documentTemplate) ->
          if (!documentTemplate.targets.contains(documentClass)) {
            problems.add("""
              |Specified target $documentClass not a valid target for DocumentData's corresponding DocumentTemplate.
              |Valid targets:
              |${documentTemplate.targets}
              """.trimMargin()
            )
          }
        }
      }
    }

    // Check for unused DocumentData variable not used in any installed DocumentTemplate field
    rowMap().forEach { (documentDataClass, documentTemplates) ->
      val codes = documentTemplates.reducedFieldCodeSet()

      val documentDataConstructor = documentDataClass.primaryConstructor
      if (documentDataConstructor == null) {
        problems.add("Null primary constructor for DocumentData $documentDataClass")
      } else {
        val documentDataParameterNames = documentDataConstructor.parameters.map { it.name }.toList()
        documentDataParameterNames.forEach { parameter ->
          if (!codes.map { it.rootKey() }.contains(parameter)) {
            problems.add("""
                |Unused DocumentData variable [$parameter] in [$documentDataClass] with no usage in installed DocumentTemplate Locales:
                |${documentTemplates.keys.joinToString("\n")}
              """.trimMargin())
          }
        }
      }
    }

    if (problems.isNotEmpty()) {
      throw BarberException(problems = problems)
    }
  }

  private fun Table<KClass<out DocumentData>, Locale, CompiledDocumentTemplate>.asBarbershop(): Barbershop {
    val barbers: LinkedHashMap<BarberKey, Barber<DocumentData, Document>> = linkedMapOf()
    cellSet().forEach { cell ->
      val documentDataClass = cell.rowKey!!
      val documentTemplate = cell.value!!
      documentTemplate.targets.forEach { documentClass ->
        val documentTemplatesBySource = row(documentTemplate.source)
        barbers[BarberKey(documentDataClass, documentClass)] = RealBarber(
          documentConstructor = documentClass.primaryConstructor!!,
          compiledDocumentTemplateLocales = documentTemplatesBySource.mapValues { it.value },
          localeResolver = localeResolver)
      }
    }
    return RealBarbershop(barbers)
  }
}