package com.wire.bots.hold.resource;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.wire.bots.hold.DAO.EventsDAO;
import com.wire.bots.hold.model.Event;
import com.wire.bots.sdk.tools.Logger;
import io.swagger.annotations.*;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

@Api
@Path("/index.html")
@Produces(MediaType.TEXT_HTML)
public class IndexResource {
    private final static MustacheFactory mf = new DefaultMustacheFactory();
    private final EventsDAO eventsDAO;

    public IndexResource(EventsDAO eventsDAO) {
        this.eventsDAO = eventsDAO;
    }

    @GET
    @Authorization("Bearer")
    @ApiOperation(value = "List all Wire conversations")
    @ApiResponses(value = {
            @ApiResponse(code = 500, message = "Something went wrong"),
            @ApiResponse(code = 200, message = "Wire conversations")})
    public Response list() {
        try {
            Model model = new Model();
            model.events = eventsDAO.listConversations();
            String html = execute(model);

            return Response.
                    ok(html, MediaType.TEXT_HTML).
                    build();
        } catch (Exception e) {
            Logger.error("IndexResource.list: %s", e);
            return Response
                    .ok(e.getMessage())
                    .status(500)
                    .build();
        }
    }

    private Mustache compileTemplate() {
        String path = "templates/index.html";
        return mf.compile(path);
    }

    private String execute(Object model) throws IOException {
        Mustache mustache = compileTemplate();
        try (StringWriter sw = new StringWriter()) {
            mustache.execute(new PrintWriter(sw), model).flush();
            return sw.toString();
        }
    }

    static class Model {
        List<Event> events;
    }
}