package com.ljyh.mei.ui.glass

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SfSymbolCatalogCoverageTest {
    @Test
    fun uiIconLiteralsArePresentInCatalog() {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(workingDirectory)) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val catalogSource = File(
            sourceRoot,
            "com/ljyh/mei/ui/glass/SfSymbolCatalog.kt",
        ).readText()
        val catalog = Regex("\\\"([A-Za-z][A-Za-z0-9.]+)\\\" to 0x")
            .findAll(catalogSource)
            .map { it.groupValues[1] }
            .toSet()
        val direct = Regex("SfIcon\\(\\s*(?:systemName\\s*=\\s*)?\\\"([A-Za-z][A-Za-z0-9.]+)\\\"")
        val named = Regex("\\bsystemName\\s*=\\s*\\\"([A-Za-z][A-Za-z0-9.]+)\\\"")
        val forwarded = Regex(
            "(?:Appearance(?:Toggle|Choice|FloatChoice)|General(?:Toggle|Choice)|AboutEntry|" +
                "ContentFeatureSetting|Storage(?:UsageRow|ActionRow)|ToggleRow|ValueRow|" +
                "BenefitCard|ShareModeButton|WikiArtworkRow|QueueModeButton)\\s*\\(" +
                "[\\s\\S]{0,220}?\\\"([A-Za-z][A-Za-z0-9.]+)\\\"",
        )
        val enumSymbols = Regex(
            "\\b[A-Z][A-Z0-9_]*\\(\\s*\\\"[^\\\"]+\\\"\\s*,\\s*[^,]+,\\s*\\\"([A-Za-z][A-Za-z0-9.]+)\\\"",
        )
        val conditionalSymbols = Regex(
            "SfIcon\\(\\s*if\\s*\\([^)]*\\)\\s*\\\"([A-Za-z][A-Za-z0-9.]+)\\\"\\s*else\\s*\\\"([A-Za-z][A-Za-z0-9.]+)\\\"",
        )
        val required = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val source = file.readText()
                val fixed = sequenceOf(direct, named, forwarded).flatMap { regex ->
                    regex.findAll(source).map { it.groupValues[1] }
                }
                val conditional = conditionalSymbols.findAll(source).flatMap { match ->
                    match.groupValues.drop(1).asSequence().filter(String::isNotEmpty)
                }
                val enums = if (file.name in setOf("MoreAction.kt", "PlayerAction.kt", "SearchScreen.kt")) {
                    enumSymbols.findAll(source).map { it.groupValues[1] }
                } else {
                    emptySequence()
                }
                fixed + conditional + enums
            }
            .toSet()
        val missing = required - catalog

        assertTrue("Missing SF Symbols: ${missing.sorted().joinToString()}", missing.isEmpty())
    }
}
