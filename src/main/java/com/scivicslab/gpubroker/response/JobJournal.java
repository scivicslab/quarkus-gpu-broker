package com.scivicslab.gpubroker.response;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import com.scivicslab.gpubroker.model.Priority;

/**
 * The submit-then-poll jobs on disk, so that a broker restart does not lose them.
 *
 * <p>Each job is up to two files in one directory: {@code <jobId>.req}, written when the job is
 * accepted (the queue, the priority, the submitter and the request body -- everything needed to run
 * it again), and {@code <jobId>.res}, written when it ends (its {@link JobResult}). Once the result
 * is written the request is no longer needed and is deleted. Each file is written under a temporary
 * name and renamed, so a restart in the middle of a write leaves the previous state, never half a file.
 *
 * <p>A plain POJO with no clock and no threads of its own: {@link JobResultStore} calls it from its
 * mailbox. A file that cannot be read is skipped with a warning, so one damaged job does not stop
 * the broker from starting.
 */
public class JobJournal {

    private static final Logger LOG = Logger.getLogger(JobJournal.class);
    private static final int FORMAT = 1;

    /** What was accepted: enough to run the job again after a restart. */
    public record Accepted(String jobId, String queueName, Priority priority, String submitterId,
                           String contentType, byte[] body, Instant createdAt) {}

    /** One job found on disk: its request if it never ended, its result if it did. */
    public record Entry(String jobId, Optional<Accepted> accepted, Optional<JobResult> result) {}

    private final Path dir;

    public JobJournal(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the jobs directory " + dir, e);
        }
    }

    public void recordAccepted(Accepted job) {
        write(job.jobId() + ".req", out -> {
            out.writeInt(FORMAT);
            out.writeUTF(job.queueName());
            out.writeUTF(job.priority().name());
            out.writeUTF(job.submitterId());
            out.writeUTF(job.contentType() == null ? "" : job.contentType());
            out.writeLong(job.createdAt().toEpochMilli());
            writeBytes(out, job.body());
        });
    }

    /** Writes the result, then deletes the request it answers. */
    public void recordResult(String jobId, JobResult result) {
        write(jobId + ".res", out -> {
            out.writeInt(FORMAT);
            out.writeUTF(result.status().name());
            out.writeUTF(result.contentType() == null ? "" : result.contentType());
            out.writeUTF(result.error() == null ? "" : result.error());
            out.writeLong(result.createdAt().toEpochMilli());
            writeBytes(out, result.body() == null ? new byte[0] : result.body());
        });
        deleteQuietly(dir.resolve(jobId + ".req"));
    }

    public void delete(String jobId) {
        deleteQuietly(dir.resolve(jobId + ".req"));
        deleteQuietly(dir.resolve(jobId + ".res"));
    }

    /** Every job on disk. A result wins over a request of the same job. */
    public List<Entry> loadAll() {
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(".req") || name.endsWith(".res")) {
                    String id = name.substring(0, name.length() - 4);
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                } else if (name.endsWith(".tmp")) {
                    deleteQuietly(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the jobs directory " + dir, e);
        }
        List<Entry> entries = new ArrayList<>();
        for (String id : ids) {
            try {
                Optional<JobResult> result = readResult(dir.resolve(id + ".res"));
                Optional<Accepted> accepted = result.isPresent()
                        ? Optional.empty() : readAccepted(id, dir.resolve(id + ".req"));
                if (result.isPresent() || accepted.isPresent()) {
                    entries.add(new Entry(id, accepted, result));
                }
            } catch (IOException | RuntimeException e) {
                LOG.warnf(e, "skipping unreadable job %s in %s", id, dir);
            }
        }
        return entries;
    }

    private Optional<JobResult> readResult(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            checkFormat(in.readInt(), file);
            JobResult.Status status = JobResult.Status.valueOf(in.readUTF());
            String contentType = emptyToNull(in.readUTF());
            String error = emptyToNull(in.readUTF());
            Instant createdAt = Instant.ofEpochMilli(in.readLong());
            byte[] body = readBytes(in);
            return Optional.of(new JobResult(status, status == JobResult.Status.DONE ? body : null,
                    contentType, error, createdAt));
        }
    }

    private Optional<Accepted> readAccepted(String jobId, Path file) throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            checkFormat(in.readInt(), file);
            String queueName = in.readUTF();
            Priority priority = Priority.valueOf(in.readUTF());
            String submitterId = in.readUTF();
            String contentType = emptyToNull(in.readUTF());
            Instant createdAt = Instant.ofEpochMilli(in.readLong());
            byte[] body = readBytes(in);
            return Optional.of(new Accepted(jobId, queueName, priority, submitterId, contentType, body, createdAt));
        }
    }

    @FunctionalInterface
    private interface Body {
        void writeTo(DataOutputStream out) throws IOException;
    }

    private void write(String name, Body body) {
        Path target = dir.resolve(name);
        Path temporary = dir.resolve(name + ".tmp");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                body.writeTo(out);
            }
            Files.write(temporary, bytes.toByteArray());
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // The job still runs from memory; only its survival of a restart is lost.
            LOG.errorf(e, "could not save %s to %s", name, dir);
            deleteQuietly(temporary);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return bytes;
    }

    private static void checkFormat(int format, Path file) throws IOException {
        if (format != FORMAT) {
            throw new IOException("unknown format " + format + " in " + file);
        }
    }

    private static String emptyToNull(String text) {
        return text.isEmpty() ? null : text;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warnf(e, "could not delete %s", file);
        }
    }
}
