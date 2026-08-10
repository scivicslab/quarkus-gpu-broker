package com.scivicslab.gpubroker.model;

/**
 * Request priority used by {@code QueueActor} to order the single deque.
 *
 * <p>FOREGROUND requests are interactive and must overtake the BACKGROUND
 * batch backlog; BACKGROUND requests are bulk work (OCR, translation, ...).
 */
public enum Priority {
    FOREGROUND,
    BACKGROUND;

    /**
     * Map the {@code X-Llm-Priority} HTTP header to a priority. Only an explicit
     * {@code background} selects BACKGROUND; anything else (including missing)
     * defaults to FOREGROUND so interactive callers are never starved by default.
     */
    public static Priority fromHeader(String header) {
        if (header != null && header.strip().equalsIgnoreCase("background")) {
            return BACKGROUND;
        }
        return FOREGROUND;
    }
}
