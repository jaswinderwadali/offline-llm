package ai.impact

import ai.impact.data.memory.HeuristicMemoryExtractor
import ai.impact.data.memory.MemoryCategory
import ai.impact.data.memory.MemoryEntity
import ai.impact.data.memory.MemoryRepository
import ai.impact.data.memory.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {

    private val extractor = HeuristicMemoryExtractor()
    private fun facts(text: String) = extractor.extract(text).map { it.content }

    @Test
    fun capturesDurableFacts() {
        assertEquals(listOf("Is a nurse"), facts("I'm a nurse and work nights."))
        assertEquals(listOf("Their dog is named Bruno"), facts("My dog is called Bruno, he's the best."))
        assertEquals(listOf("Lives in Toronto"), facts("I live in Toronto"))
        assertEquals(listOf("Works at Google"), facts("I work at Google"))
        assertEquals(listOf("Is vegetarian"), facts("I'm vegetarian btw"))
        assertEquals(listOf("Loves hiking in the mountains"), facts("I love hiking in the mountains!"))
        assertEquals(listOf("Goal: run a marathon this year"), facts("My goal is to run a marathon this year."))
    }

    @Test
    fun assignsCategories() {
        assertEquals(MemoryCategory.Work, extractor.extract("I'm a teacher.").single().category)
        assertEquals(MemoryCategory.Relationships, extractor.extract("My wife is named Priya.").single().category)
        assertEquals(MemoryCategory.Wellbeing, extractor.extract("I have anxiety").single().category)
    }

    @Test
    fun ignoresFeelingsAndRequests() {
        assertTrue(facts("I'm tired today").isEmpty())
        assertTrue(facts("I'm feeling a bit low").isEmpty())
        assertTrue(facts("I want to know how to cook rice").isEmpty())
        assertTrue(facts("Can you help me plan my week?").isEmpty())
        assertTrue(facts("I love that idea").isEmpty())
        assertTrue(facts("I like it").isEmpty())
    }

    @Test
    fun neverStoresNegativeSelfTalk() {
        assertTrue(facts("I'm a failure").isEmpty())
        assertTrue(facts("I'm a burden to everyone").isEmpty())
        assertTrue(facts("I hate myself").isEmpty())
        assertTrue(facts("I hate my life").isEmpty())
        assertTrue(facts("I'm a terrible person").isEmpty())
    }

    @Test
    fun convertsToThirdPerson() {
        assertEquals(listOf("Is working towards: get their degree"), facts("I'm trying to get my degree"))
    }

    @Test
    fun promptContextRespectsBudget() {
        val many = (1..100).map {
            MemoryEntity(id = it.toLong(), content = "Fact number $it about the user", category = MemoryCategory.Other,
                source = MemorySource.Auto, createdAt = 0, updatedAt = 0)
        }
        val context = MemoryRepository.formatForPrompt(many)!!
        assertTrue(context.length <= 720)
        assertTrue(context.startsWith("- Fact number 1 "))
        assertNull(MemoryRepository.formatForPrompt(emptyList()))
    }
}
