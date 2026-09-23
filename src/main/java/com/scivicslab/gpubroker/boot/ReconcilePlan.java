package com.scivicslab.gpubroker.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What one round of probing implies for the registry, decided without touching the actor tree.
 *
 * <p>Separated from {@link JobQueueRegistry#reconcile} so the rules can be stated and tested on
 * their own: everything here is a comparison of two maps, while carrying them out means creating
 * and closing actors. See {@code PeriodicRediscovery_260923_oo01}.
 */
public final class ReconcilePlan {

    /**
     * One address to (re)register. {@code leavingQueue} is the queue it must be taken out of
     * first, or null when it is not registered anywhere yet.
     */
    public record Change(EndpointSurveyor.Found found, String leavingQueue) {}

    private ReconcilePlan() {
    }

    /**
     * @param registered address -> the queue it is registered in, as the registry has it now
     * @param found      every address that answered this round, with the queue it now claims
     * @return one {@link Change} per address that answers under a queue it is not registered in.
     *         An address registered under the queue it already claims yields nothing, and so does
     *         a registered address missing from {@code found}: an endpoint that answered nothing
     *         is left in place, because a server that is merely restarting would otherwise leave
     *         and re-enter the tree on every failure.
     */
    public static List<Change> of(Map<String, String> registered, List<EndpointSurveyor.Found> found) {
        List<Change> changes = new ArrayList<>();
        for (EndpointSurveyor.Found f : found) {
            String nowIn = registered.get(f.info().address());
            if (f.info().queueName().equals(nowIn)) {
                continue;
            }
            changes.add(new Change(f, nowIn));
        }
        return changes;
    }
}
