package com.scivicslab.gpubroker.capacity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * How many jobs each server really computes at once, as far as the broker knows: what the script
 * that started the server declared, and what the broker observed on the server's {@code /metrics}.
 * Decides the slots of an {@code AiServiceEndpoint} in this order: the operator's {@code
 * max-concurrency} in the configuration file, a declaration that belongs to the running server,
 * an observation of the running server, the kind's default. See {@code
 * DeclaredEndpointConcurrency_261103_oo01}.
 *
 * <p>A plain POJO with no threads of its own: it is wrapped as an actor, and every call comes from
 * its mailbox. Declarations are written to {@code capacity-declarations.json} in the jobs
 * directory, under a temporary name and renamed, so they survive a broker restart; observations are
 * kept in memory only, because the next survey makes them again within a minute.
 */
public class EndpointCapacity {

    private static final Logger LOG = Logger.getLogger(EndpointCapacity.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String FILE_NAME = "capacity-declarations.json";

    /** What a start script declared for one run of a server. */
    public record Declaration(int maxConcurrency, double serverStartTime) {}

    /** The number of jobs one run of a server was seen computing while it kept others waiting. */
    public record Observation(int maxConcurrency, double serverStartTime) {}

    private final Path file;
    private final Map<String, Declaration> declarations = new TreeMap<>();
    private final Map<String, Observation> observations = new TreeMap<>();

    /** @param directory where declarations are kept, or null to keep them in memory only */
    public EndpointCapacity(Path directory) {
        this.file = directory == null ? null : directory.resolve(FILE_NAME);
        load();
    }

    /** Records a declaration for {@code address}, replacing an earlier one, and writes it to disk. */
    public void declare(String address, Declaration declaration) {
        declarations.put(address, declaration);
        save();
    }

    /**
     * The slots {@code address} gets. Updates the observation of the address first: an observation
     * of an earlier run is dropped, and a server that keeps jobs waiting because it is full is
     * recorded as computing at most what it computes now.
     *
     * @param kindDefault the default of the endpoint's kind
     * @param override    the operator's {@code max-concurrency} for the address, if any
     * @param run         what the server says in {@code /metrics}, or empty if it says nothing
     */
    public int slotsFor(String address, int kindDefault, OptionalInt override, Optional<ServerRun> run) {
        run.ifPresent(r -> observe(address, r));
        if (override.isPresent()) {
            return override.getAsInt();
        }
        Observation observed = run.map(r -> observations.get(address))
                .filter(o -> run.get().sameRunAs(o.serverStartTime())).orElse(null);
        Declaration declared = run.map(r -> declarations.get(address))
                .filter(d -> run.get().sameRunAs(d.serverStartTime())).orElse(null);
        if (declared != null) {
            return observed == null ? declared.maxConcurrency()
                                    : Math.min(declared.maxConcurrency(), observed.maxConcurrency());
        }
        if (observed != null) {
            return observed.maxConcurrency();
        }
        return kindDefault;
    }

    private void observe(String address, ServerRun run) {
        Observation known = observations.get(address);
        if (known != null && !run.sameRunAs(known.serverStartTime())) {
            observations.remove(address);
            known = null;
        }
        if (run.waitingForCapacity() > 0 && run.running() > 0
                && (known == null || run.running() < known.maxConcurrency())) {
            observations.put(address, new Observation(run.running(), run.startTime()));
            LOG.infof("%s keeps %d jobs waiting because it is full: its slots follow the %d it computes",
                    address, run.waitingForCapacity(), run.running());
        }
    }

    /** The declarations and the observations, by address, for {@code GET /capacity}. */
    public Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("declarations", new TreeMap<>(declarations));
        report.put("observations", new TreeMap<>(observations));
        return report;
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            declarations.putAll(MAPPER.readValue(file.toFile(), new TypeReference<Map<String, Declaration>>() {}));
        } catch (IOException e) {
            LOG.warnf(e, "cannot read %s; starting without declarations", file);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            MAPPER.writeValue(tmp.toFile(), declarations);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.errorf(e, "cannot write %s; the declarations are kept in memory until the broker stops", file);
        }
    }
}
