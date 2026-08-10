package com.scivicslab.gpubroker.actor;

/**
 * The single root of the Actor tree. {@code ActorSystem} itself is a
 * container ({@code actorOf}/{@code getActor}), not a node in the
 * {@code createChild} parent-child graph — this empty POJO, actor-ified
 * once via {@code ActorSystem.actorOf("root", new ROOT())}, is what gives
 * the tree an actual, traceable root. Every {@code JobQueue} is created as
 * this actor's child.
 */
public final class ROOT {
}
