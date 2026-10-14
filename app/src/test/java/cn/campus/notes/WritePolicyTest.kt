package cn.campus.notes

import cn.campus.notes.domain.*
import org.junit.Assert.assertEquals
import org.junit.Test

class WritePolicyTest {
    @Test fun sandboxNonSensitiveWriteCanProceed() {
        assertEquals(WriteDecision.ALLOW, WritePolicy.evaluate(WriteScope.APP_SANDBOX, false))
    }
    @Test fun externalWritesAlwaysRequireConfirmation() {
        assertEquals(WriteDecision.REQUIRE_CONFIRMATION, WritePolicy.evaluate(WriteScope.EXTERNAL, true))
        assertEquals(WriteDecision.REQUIRE_CONFIRMATION, WritePolicy.evaluate(WriteScope.EXTERNAL, false))
    }
    @Test fun sensitiveSandboxWriteRequiresConfirmation() {
        assertEquals(WriteDecision.REQUIRE_CONFIRMATION, WritePolicy.evaluate(WriteScope.APP_SANDBOX, true))
    }
}
