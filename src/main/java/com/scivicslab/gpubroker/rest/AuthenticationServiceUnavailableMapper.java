package com.scivicslab.gpubroker.rest;

import io.quarkus.oidc.OIDCException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A management call whose token cannot be checked because the realm cannot be reached is refused with
 * 503, not with the 500 an unhandled {@link OIDCException} would become: the caller did nothing wrong and
 * may try again later. The call is refused either way, which is what matters
 * ({@code ManagementAuthentication_261115_oo01}). Only methods that need a token reach this; the rest of
 * the broker never asks the realm anything.
 */
@Provider
public class AuthenticationServiceUnavailableMapper implements ExceptionMapper<OIDCException> {

    @Override
    public Response toResponse(OIDCException e) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.TEXT_PLAIN)
                .entity("the authentication service cannot be reached; the declaration was not accepted")
                .build();
    }
}
