package io.github.wolverr.bivouac.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryPlannerTest {
    // KV bytes per token as model_probe reports them: 256 MB (4B/9B) and 96 MB (2B) at ctx 8192.
    private val qwen4b = ModelProbe(n_ctx_train = 262144, kv_full_per_token = 256_000_000L / 8192)
    private val qwen2b = ModelProbe(n_ctx_train = 262144, kv_full_per_token = 96_000_000L / 8192)

    private val gb = 1_000_000_000L
    private val idleAvail = (1.5 * gb).toLong()  // little free at idle, as on Xiaomi phones

    @Test fun eachTierLoadsItsModelWithLittleFreeRam() {
        // (total RAM Android reports, model file) for the 4 / 6 / 8 / 12 GB tiers in catalog.json
        val tiers = listOf(
            3_700_000_000L to (1_280_835_840L to qwen2b),
            5_700_000_000L to (2_293_388_448L to qwen4b),
            8_020_000_000L to (2_740_937_888L to qwen4b),
            11_500_000_000L to (5_680_522_464L to qwen4b),
        )
        for ((total, model) in tiers) {
            val plan = MemoryPlanner.plan(model.first, model.second, idleAvail, total)
            assertNotNull("total $total", plan)
            assertEquals(8192, plan!!.nCtx)
        }
    }

    @Test fun eightGbPhoneRepacksThe4b() {
        // Measured on the Redmi: 3.9 GB with repacking, prompt reading 2x faster.
        val plan = MemoryPlanner.plan(2_740_937_888L, qwen4b, 3_100_000_000L, 8_020_000_000L)!!
        assertEquals(true, plan.repack)
        assertEquals(8192, plan.nCtx)
    }

    @Test fun twelveGbPhoneRunsThe9bWithoutRepackWhenLittleIsFree() {
        val plan = MemoryPlanner.plan(5_680_522_464L, qwen4b, idleAvail, 11_500_000_000L)!!
        assertFalse(plan.repack)
    }

    @Test fun redmiAtIdleLoadsThe4b() {
        // Redmi Note 15 Pro: 7.83 GB total, 3.1 GB free at idle; this was refused before.
        val plan = MemoryPlanner.plan(2_740_937_888L, qwen4b, 3_100_000_000L, 8_020_000_000L)
        assertNotNull(plan)
    }

    @Test fun nineBDoesNotFitAnEightGbPhone() {
        assertNull(MemoryPlanner.plan(5_680_522_464L, qwen4b, 3_100_000_000L, 8_020_000_000L))
    }

    @Test fun freeRamStillCountsWhenItIsLarger() {
        // Plenty free right now: the 2B gets repacked even on a small phone.
        val plan = MemoryPlanner.plan(1_280_835_840L, qwen2b, 4 * gb, 3_700_000_000L)
        assertNotNull(plan)
        assertEquals(true, plan!!.repack)
    }

    @Test fun safetyAfterACrashShrinksTheBudget() {
        assertNull(MemoryPlanner.plan(5_680_522_464L, qwen4b, idleAvail, 11_500_000_000L, safety = 0.75))
    }
}
