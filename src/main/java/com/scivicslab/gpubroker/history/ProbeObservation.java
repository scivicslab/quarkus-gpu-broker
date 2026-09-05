package com.scivicslab.gpubroker.history;

/**
 * One address's answer to one round of probing, as {@code
 * StatusHistoryRecorder} observed it. Carries {@code queueName} because an
 * address that stopped answering is no longer in any {@code
 * EndpointProbe.survey} result, and the liveness band still has to know
 * which queue's card to draw its row on.
 */
public record ProbeObservation(String address, String queueName, boolean responded) {
}
