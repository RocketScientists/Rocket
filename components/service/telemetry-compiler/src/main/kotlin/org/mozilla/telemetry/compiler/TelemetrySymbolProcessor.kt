/* -*- Mode: Kotlin; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.telemetry.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate
import org.mozilla.telemetry.annotation.TelemetryDoc
import org.mozilla.telemetry.annotation.TelemetryExtra
import java.io.File
import java.io.IOException
import java.io.PrintWriter

class TelemetrySymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    companion object {
        const val FILE_README = "/docs/events.md"
        const val FILE_AMPLITUDE_MAPPING = "/docs/view.sql"
        const val FILE_SOURCE_SQL = "view-replace.sql"
        const val FILE_SOURCE_SQL_PLACE_HOLDER = "---REPLACE---ME---"
    }

    // TODO: TelemetryEvent's fields are private, I'll create a PR to make them public so I can
    // test the ping format in compile time.
    object TelemetryEventConstant {
        const val MAX_LENGTH_CATEGORY = 30
        const val MAX_LENGTH_METHOD = 20
        const val MAX_LENGTH_OBJECT = 20
        const val MAX_LENGTH_VALUE = 80
        const val MAX_EXTRA_KEYS = 200
        const val MAX_LENGTH_EXTRA_KEY = 15
        const val MAX_LENGTH_EXTRA_VALUE = 80
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(TelemetryDoc::class.qualifiedName!!)
        val annotatedFunctions = symbols.filterIsInstance<KSFunctionDeclaration>()
            .filter { it.validate() }
            .toList()

        if (annotatedFunctions.isEmpty()) {
            return emptyList()
        }

        val projectRootDir = options["projectRootDir"]
        if (projectRootDir == null) {
            logger.error("Missing required option: projectRootDir")
            return emptyList()
        }

        try {
            val header = "| Event | category | method | object | value | extra |\n" +
                    "| ---- | ---- | ---- | ---- | ---- | ---- |\n"
            genDoc(annotatedFunctions, header, "$projectRootDir$FILE_README", '|')
            genSQL(annotatedFunctions, "$projectRootDir$FILE_AMPLITUDE_MAPPING")
        } catch (e: Exception) {
            logger.error("Exception while creating Telemetry related documents: ${e.message}")
            e.printStackTrace()
        }

        return emptyList()
    }

    private fun genDoc(
        annotatedFunctions: List<KSFunctionDeclaration>,
        header: String,
        path: String,
        separator: Char
    ) {
        val file = File(path)
        if (file.exists()) {
            file.delete()
        }
        val directory = File(file.parentFile.absolutePath)
        directory.mkdirs()

        PrintWriter(file).use { printWriter ->
            printWriter.println(header.trimEnd())

            val lookup = mutableMapOf<String, Boolean>()

            for (function in annotatedFunctions) {
                val annotation = function.annotations
                    .find { it.shortName.asString() == "TelemetryDoc" }
                    ?: continue

                val telemetryDoc = parseTelemetryDoc(annotation)
                verifyEventFormat(telemetryDoc)

                val result = verifyEventDuplication(telemetryDoc, lookup)
                if (result != null) {
                    throw IllegalArgumentException("Duplicate event combination: $telemetryDoc\n$result")
                }

                val sb = StringBuilder()
                    .append(separator).append(telemetryDoc.name).append(separator)
                    .append(telemetryDoc.category).append(separator)
                    .append(telemetryDoc.method).append(separator)
                    .append(telemetryDoc.`object`).append(separator)
                    .append('"').append(telemetryDoc.value).append('"').append(separator)

                sb.append('"')
                for (extra in telemetryDoc.extras) {
                    sb.append("${extra.name}=${extra.value},")
                }
                sb.append('"')

                printWriter.println(sb.toString())
            }
        }
    }

    private fun genSQL(annotatedFunctions: List<KSFunctionDeclaration>, path: String) {
        val file = File(path)
        if (file.exists()) {
            file.delete()
        }
        val directory = File(file.parentFile.absolutePath)
        directory.mkdirs()

        PrintWriter(file).use { printWriter ->
            val sb = StringBuilder()
            val lookup = mutableMapOf<String, Boolean>()

            for (function in annotatedFunctions) {
                val annotation = function.annotations
                    .find { it.shortName.asString() == "TelemetryDoc" }
                    ?: continue

                val telemetryDoc = parseTelemetryDoc(annotation)

                if (telemetryDoc.skipAmplitude) {
                    continue
                }

                verifyAmplitudeMappingFormat(telemetryDoc)
                val result = verifyEventDuplication(telemetryDoc, lookup)
                if (result != null) {
                    throw IllegalArgumentException("Duplicate event combination: $telemetryDoc\n$result")
                }

                val partValue = StringBuilder()
                val telemetryValue = telemetryDoc.value
                if (telemetryValue.isNotEmpty()) {
                    partValue.append("AND (event_value IN (")
                    val split = telemetryValue.split(",").toMutableList()
                    var hasNull = false
                    for (value in split) {
                        if (value == "null") {
                            hasNull = true
                            continue
                        }
                        partValue.append("'$value', ")
                    }
                    partValue.deleteCharAt(partValue.length - 1).deleteCharAt(partValue.length - 1)
                    if (hasNull) {
                        partValue.append(") OR event_value IS NULL) ")
                    } else {
                        partValue.append(") ) ")
                    }
                } else {
                    partValue.append("AND event_value IS NULL ")
                }

                val event = "        WHEN (event_category IN ('${telemetryDoc.category}') ) AND (event_method IN ('${telemetryDoc.method}') ) AND (event_object IN ('${telemetryDoc.`object`}') ) $partValue THEN 'Rocket -  ${telemetryDoc.name.replace("'", "\\'")}' "
                sb.append(event)
                sb.append("\n")
            }

            // Read template file from resources and replace placeholder
            val templateStream = javaClass.getResourceAsStream("/$FILE_SOURCE_SQL")
            if (templateStream != null) {
                templateStream.bufferedReader().use { reader ->
                    reader.lines().forEach { line ->
                        val replacedLine = line.replace(FILE_SOURCE_SQL_PLACE_HOLDER, sb.toString())
                        printWriter.println(replacedLine)
                    }
                }
            } else {
                logger.warn("Template file $FILE_SOURCE_SQL not found in resources")
            }
        }
    }

    private fun verifyAmplitudeMappingFormat(telemetryDoc: TelemetryDocData) {
        val pattern = "[A-Za-z0-9,_]*".toRegex()
        if (!telemetryDoc.`object`.matches(pattern)) {
            logger.error("Contain invalid chars in Telemetry object: $telemetryDoc")
        }
        if (!telemetryDoc.method.matches(pattern)) {
            logger.error("Contain invalid chars in Telemetry method: $telemetryDoc")
        }
        if (!telemetryDoc.value.matches(pattern)) {
            logger.error("Contain invalid chars in Telemetry value: $telemetryDoc")
        }
    }

    private fun verifyEventDuplication(
        telemetryDoc: TelemetryDocData,
        lookup: MutableMap<String, Boolean>
    ): String? {
        val key = StringBuilder().apply {
            append(telemetryDoc.category)
            append(telemetryDoc.method)
            append(telemetryDoc.`object`)
            append(telemetryDoc.value)
            for (extra in telemetryDoc.extras) {
                append(extra.name)
            }
        }.toString()

        if (lookup.containsKey(key)) {
            return key
        }
        lookup[key] = true
        return null
    }

    private fun verifyEventFormat(telemetryDoc: TelemetryDocData) {
        val category = telemetryDoc.category
        if (category.length > TelemetryEventConstant.MAX_LENGTH_CATEGORY) {
            throw IllegalArgumentException("The length of category is too long: $category")
        }
        val method = telemetryDoc.method
        if (method.length > TelemetryEventConstant.MAX_LENGTH_METHOD) {
            throw IllegalArgumentException("The length of method is too long: $method")
        }
        val obj = telemetryDoc.`object`
        if (obj.length > TelemetryEventConstant.MAX_LENGTH_OBJECT) {
            throw IllegalArgumentException("The length of object is too long: $obj")
        }
        val value = telemetryDoc.value
        if (value.length > TelemetryEventConstant.MAX_LENGTH_VALUE) {
            throw IllegalArgumentException("The length of value is too long: $value")
        }
        val extras = telemetryDoc.extras
        if (extras.size > TelemetryEventConstant.MAX_EXTRA_KEYS) {
            throw IllegalArgumentException("Too many extras")
        }
        for (extra in extras) {
            val eName = extra.name
            val eVal = extra.value
            if (eName.length > TelemetryEventConstant.MAX_LENGTH_EXTRA_KEY) {
                throw IllegalArgumentException("The length of extra key is too long: $eName")
            }
            if (eVal.length > TelemetryEventConstant.MAX_LENGTH_EXTRA_VALUE) {
                throw IllegalArgumentException("The length of extra value is too long: $eVal")
            }
        }
    }

    private fun parseTelemetryDoc(annotation: com.google.devtools.ksp.symbol.KSAnnotation): TelemetryDocData {
        val arguments = annotation.arguments.associateBy { it.name?.asString() }

        val name = arguments["name"]?.value as? String ?: ""
        val value = arguments["value"]?.value as? String ?: ""
        val category = arguments["category"]?.value as? String ?: ""
        val method = arguments["method"]?.value as? String ?: ""
        val obj = arguments["object"]?.value as? String ?: ""
        val skipAmplitude = arguments["skipAmplitude"]?.value as? Boolean ?: false

        @Suppress("UNCHECKED_CAST")
        val extrasArray = arguments["extras"]?.value as? ArrayList<com.google.devtools.ksp.symbol.KSAnnotation> ?: arrayListOf()
        val extras = extrasArray.map { extraAnnotation ->
            val extraArgs = extraAnnotation.arguments.associateBy { it.name?.asString() }
            TelemetryExtraData(
                name = extraArgs["name"]?.value as? String ?: "",
                value = extraArgs["value"]?.value as? String ?: ""
            )
        }

        return TelemetryDocData(
            name = name,
            value = value,
            category = category,
            method = method,
            `object` = obj,
            extras = extras,
            skipAmplitude = skipAmplitude
        )
    }
}

data class TelemetryDocData(
    val name: String,
    val value: String,
    val category: String,
    val method: String,
    val `object`: String,
    val extras: List<TelemetryExtraData>,
    val skipAmplitude: Boolean = false
)

data class TelemetryExtraData(
    val name: String,
    val value: String
)