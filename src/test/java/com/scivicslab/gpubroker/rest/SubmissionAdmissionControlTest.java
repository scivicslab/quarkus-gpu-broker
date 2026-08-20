package com.scivicslab.gpubroker.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure-POJO tests: no CDI, no HTTP — {@code tryAdmit}/{@code release} called directly. */
@DisplayName("SubmissionAdmissionControl — per-(submitter, queue) in-flight limit")
class SubmissionAdmissionControlTest {

    @Test
    void tryAdmit_allowsUpToTheLimit_thenRejects() {
        SubmissionAdmissionControl control = new SubmissionAdmissionControl(2);

        assertTrue(control.tryAdmit("s1", "q1"));
        assertTrue(control.tryAdmit("s1", "q1"));
        assertFalse(control.tryAdmit("s1", "q1"));   // limit reached
    }

    @Test
    void release_freesASlotForTheNextAdmit() {
        SubmissionAdmissionControl control = new SubmissionAdmissionControl(1);

        assertTrue(control.tryAdmit("s1", "q1"));
        assertFalse(control.tryAdmit("s1", "q1"));

        control.release("s1", "q1");

        assertTrue(control.tryAdmit("s1", "q1"));
    }

    @Test
    void limitsAreIndependent_perSubmitterAndPerQueue() {
        SubmissionAdmissionControl control = new SubmissionAdmissionControl(1);

        assertTrue(control.tryAdmit("s1", "q1"));
        assertFalse(control.tryAdmit("s1", "q1"));      // s1/q1 is full

        assertTrue(control.tryAdmit("s2", "q1"));        // different submitter, same queue: unaffected
        assertTrue(control.tryAdmit("s1", "q2"));         // same submitter, different queue: unaffected
    }
}
