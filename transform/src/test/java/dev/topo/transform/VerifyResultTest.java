package dev.topo.transform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifyResultTest {

    @Test
    void defaultPasses() {
        var result = new VerifyResult();
        assertTrue(result.passed());
        assertTrue(result.errors.isEmpty());
    }

    @Test
    void publicMissingFails() {
        var result = new VerifyResult();
        result.publicMissing = 1;
        assertFalse(result.passed());
    }

    @Test
    void blockMismatchFails() {
        var result = new VerifyResult();
        result.blockMismatches = 1;
        assertFalse(result.passed());
    }

    @Test
    void signatureMismatchFails() {
        var result = new VerifyResult();
        result.signatureMismatches = 1;
        assertFalse(result.passed());
    }

    @Test
    void constMismatchFails() {
        var result = new VerifyResult();
        result.constMismatches = 1;
        assertFalse(result.passed());
    }

    @Test
    void stageOrderViolationFails() {
        var result = new VerifyResult();
        result.stageOrderViolations = 1;
        assertFalse(result.passed());
    }

    @Test
    void allFieldsZeroPasses() {
        var result = new VerifyResult();
        result.classMemberMissing = 0;
        result.pipelineEdgeMismatches = 0;
        result.templateInstantiationMissing = 0;
        result.sharedMutableFieldWrites = 0;
        assertTrue(result.passed());
    }

    @Test
    void sharedMutableFieldWriteFails() {
        var result = new VerifyResult();
        result.sharedMutableFieldWrites = 1;
        assertFalse(result.passed());
    }

    @Test
    void javacDeferredNoteDocumentsCheck9() {
        // Check 9 (constraint satisfaction) is delegated to javac and
        // owns no counter. The static note pins the contract so
        // downstream consumers can surface the delegation.
        assertTrue(VerifyResult.javacDeferredNote.toLowerCase().contains("javac"));
        assertTrue(VerifyResult.javacDeferredNote.toLowerCase().contains("check 9"));
    }

    @Test
    void multipleFailures() {
        var result = new VerifyResult();
        result.publicMissing = 2;
        result.blockMismatches = 1;
        result.errors.add("error 1");
        result.errors.add("error 2");
        assertFalse(result.passed());
        assertEquals(2, result.errors.size());
    }
}
