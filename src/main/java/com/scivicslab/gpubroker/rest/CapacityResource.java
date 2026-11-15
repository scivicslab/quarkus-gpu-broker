package com.scivicslab.gpubroker.rest;

import java.util.Map;
import java.util.regex.Pattern;

import com.scivicslab.gpubroker.capacity.EndpointCapacity;
import com.scivicslab.pojoactor.core.ActorRef;

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Where the script that started a server declares how many jobs the server computes at once, and
 * where anyone can read what is declared and observed. Declaring needs a token; reading does not. The declaration takes effect at the next
 * survey, within a minute, and only while the server's start time is the one declared. See {@code
 * DeclaredEndpointConcurrency_261103_oo01}.
 *
 * <p>{@code @Blocking} because both methods call {@code ask(...).join()}.
 */
@Path("/capacity")
public class CapacityResource {

    /** The realm role a server's start script holds, through its service account. */
    public static final String WRITER_ROLE = "broker-capacity-writer";

    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.-]+:[0-9]{1,5}");

    /** The body of {@code PUT /capacity/{address}}. */
    public record DeclarationBody(Integer maxConcurrency, Double serverStartTime) {}

    @Inject
    ActorRef<EndpointCapacity> capacity;

    /**
     * The one management operation: it changes what the broker believes about a server, so it needs a
     * token from the realm with the role {@value #WRITER_ROLE}. Every other method of this class, and every
     * other endpoint of the broker, is open ({@code ManagementAuthentication_261115_oo01}).
     */
    @PUT
    @Path("/{address}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    @RolesAllowed(WRITER_ROLE)
    public Response declare(@PathParam("address") String address, DeclarationBody body) {
        if (!ADDRESS.matcher(address).matches()) {
            throw new BadRequestException("address must be host:port");
        }
        if (body == null || body.maxConcurrency() == null || body.maxConcurrency() < 1 || body.maxConcurrency() > 1024) {
            throw new BadRequestException("maxConcurrency must be between 1 and 1024");
        }
        if (body.serverStartTime() == null || body.serverStartTime() <= 0) {
            throw new BadRequestException("serverStartTime must be the server's process_start_time_seconds");
        }
        EndpointCapacity.Declaration declaration =
                new EndpointCapacity.Declaration(body.maxConcurrency(), body.serverStartTime());
        capacity.tell(c -> c.declare(address, declaration)).join();
        return Response.noContent().build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Map<String, Object> report() {
        return capacity.ask(EndpointCapacity::report).join();
    }
}
