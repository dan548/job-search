package th.sibraine.jobagent.candidate.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TesseractResumeOcrEngineTest {
    @Test
    fun `groups TSV words into positioned lines`() {
        val tsv = """level	page_num	block_num	par_num	line_num	word_num	left	top	width	height	conf	text
5	1	1	1	1	1	10	20	40	12	96.0	Backend
5	1	1	1	1	2	55	20	60	12	94.0	Engineer
5	1	1	1	2	1	10	40	35	12	90.0	Kotlin
"""
        val engine = TesseractResumeOcrEngine(TesseractOcrProperties())

        val blocks = engine.parseTsv(tsv, pageNumber = 2, startingOrder = 5)

        assertEquals(2, blocks.size)
        assertEquals("Backend Engineer", blocks[0].text)
        assertEquals(2, blocks[0].pageNumber)
        assertEquals(5, blocks[0].order)
        assertEquals(105.0, blocks[0].boundingBox.width)
        assertTrue(blocks[0].confidence!! > 0.94)
        assertEquals(6, blocks[1].order)
    }
}
