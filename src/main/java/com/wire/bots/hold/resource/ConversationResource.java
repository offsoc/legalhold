package com.wire.bots.hold.resource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.wire.bots.hold.DAO.AccessDAO;
import com.wire.bots.hold.DAO.EventsDAO;
import com.wire.bots.hold.filters.ServiceAuthorization;
import com.wire.bots.hold.model.Event;
import com.wire.bots.hold.model.LHAccess;
import com.wire.bots.hold.utils.Cache;
import com.wire.bots.hold.utils.Collector;
import com.wire.bots.hold.utils.PdfGenerator;
import com.wire.helium.API;
import com.wire.xenon.backend.models.Conversation;
import com.wire.xenon.backend.models.Member;
import com.wire.xenon.backend.models.SystemMessage;
import com.wire.xenon.models.*;
import com.wire.xenon.tools.Logger;
import io.swagger.annotations.*;

import javax.annotation.Nullable;
import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.UUID;

@Api
@Path("/conv/{conversationId}")
@Produces("application/pdf")
public class ConversationResource {
    private final static MustacheFactory mf = new DefaultMustacheFactory();
    private final EventsDAO eventsDAO;
    private final AccessDAO accessDAO;
    private final Client httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private API api;

    public ConversationResource(EventsDAO eventsDAO, AccessDAO accessDAO, Client httpClient) {
        this.eventsDAO = eventsDAO;
        this.accessDAO = accessDAO;
        this.httpClient = httpClient;
        api = getLHApi();
    }

    @GET
    @ServiceAuthorization
    @ApiOperation(value = "Render Wire events for this conversation")
    @ApiResponses(value = {
            @ApiResponse(code = 500, message = "Something went wrong"),
            @ApiResponse(code = 200, message = "Wire events")})
    public Response list(@ApiParam @PathParam("conversationId") UUID conversationId,
                         @ApiParam @QueryParam("html") boolean isHtml) {
        try {
            List<Event> events = eventsDAO.listAllAsc(conversationId);

            testAPI();

            Cache cache = new Cache(api);
            Collector collector = new Collector(cache);
            for (Event event : events) {
                switch (event.type) {
                    case "conversation.create": {
                        onConversationCreate(collector, event);
                    }
                    break;
                    case "conversation.rename": {
                        onConversationRename(collector, event);
                    }
                    break;
                    case "conversation.otr-message-add.new-text": {
                        onText(collector, event);
                    }
                    break;
                    case "conversation.otr-message-add.edit-text": {
                        onTextEdit(collector, event);
                    }
                    break;
                    case "conversation.otr-message-add.delete-text": {
                        onTextDelete(collector, event);
                    }
                    break;
                    case "conversation.otr-message-add.new-image": {
                        onImage(collector, event);
                    }
                    break;
                    case "conversation.otr-message-add.new-attachment": {
                        onAttachment(collector, event);
                    }
                    break;
                    case "conversation.otr-message-add.new-audio": {
                        onAudio(collector, event);
                    }
                    break;
                    case "conversation.otr-message-add.new-video": {
                        onVideo(collector, event);
                    }
                    break;
                    case "conversation.otr-message-add.call": {
                        onCall(collector, event);
                    }
                    break;
                    case "conversation.member-join": {
                        onMember(collector, event, "added");
                    }
                    break;
                    case "conversation.member-leave": {
                        onMember(collector, event, "removed");
                    }
                    break;
                }
            }

            Collector.Conversation conversation = collector.getConversation();
            String html = execute(conversation);

            if (isHtml)
                return Response.
                        ok(html, MediaType.TEXT_HTML).
                        build();

            byte[] out = PdfGenerator.convert(html, "file:/opt/hold");
            return Response.
                    ok(out, "application/pdf").
                    build();
        } catch (Exception e) {
            Logger.exception("ConversationResource.list: %s", e, e.getMessage());
            return Response
                    .serverError()
                    .status(500)
                    .build();
        }
    }

    private void onImage(Collector collector, Event event) {
        try {
            ImageMessage message = mapper.treeToValue(event.payload, ImageMessage.class);
            collector.add(message);
        } catch (Exception e) {
            Logger.exception("onImage: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onText(Collector collector, Event event) {
        try {
            TextMessage message = mapper.treeToValue(event.payload, TextMessage.class);
            collector.add(message);
        } catch (Exception e) {
            Logger.exception("onText: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onTextEdit(Collector collector, Event event) {
        try {
            EditedTextMessage message = mapper.treeToValue(event.payload, EditedTextMessage.class);
            String text = String.format("**%s** edited: %s",
                    getUserName(message.getUserId()), message.getText());
            collector.addSystem(text, message.getTime(), event.type);
        } catch (Exception e) {
            Logger.exception("onTextEdit: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onTextDelete(Collector collector, Event event) {
        try {
            DeletedTextMessage message = mapper.treeToValue(event.payload, DeletedTextMessage.class);
            UUID deletedMessageId = message.getDeletedMessageId();
            String orgText = getText(deletedMessageId);
            String text = String.format("**%s** deleted text: '%s'",
                    getUserName(message.getUserId()),
                    orgText);
            collector.addSystem(text, message.getTime(), event.type);
        } catch (Exception e) {
            Logger.exception("onTextDelete: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onCall(Collector collector, Event event) {
        try {
            CallingMessage message = mapper.treeToValue(event.payload, CallingMessage.class);
            String json = message.getContent().replace("\\", "");
            _CallingContent content = mapper.readValue(json, _CallingContent.class);
            String text = String.format("**%s** called: %s", getUserName(message.getUserId()), content.type);
            collector.addSystem(text, message.getTime(), event.type);
        } catch (Exception e) {
            Logger.exception("onCall: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onAttachment(Collector collector, Event event) {
        try {
            AttachmentMessage message = mapper.treeToValue(event.payload, AttachmentMessage.class);
            collector.add(message);
        } catch (Exception e) {
            Logger.exception("onAttachment: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onAudio(Collector collector, Event event) {
        try {
            AudioMessage message = mapper.treeToValue(event.payload, AudioMessage.class);
            collector.add(message);
        } catch (Exception e) {
            Logger.exception("onAudio: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onVideo(Collector collector, Event event) {
        try {
            VideoMessage message = mapper.treeToValue(event.payload, VideoMessage.class);
            collector.add(message);
        } catch (Exception e) {
            Logger.exception("onVideo: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onMember(Collector collector, Event event, String label) {
        try {
            SystemMessage msg = mapper.treeToValue(event.payload, SystemMessage.class);
            for (UUID userId : msg.users) {
                String format = String.format("**%s** %s **%s**",
                        getUserName(msg.from),
                        label,
                        getUserName(userId));
                collector.addSystem(format, msg.time, event.type);
            }
        } catch (Exception e) {
            Logger.exception("onMember: %s conv: %s, msg: %s error: %s", e, event.type, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onConversationCreate(Collector collector, Event event) {
        try {
            SystemMessage msg = mapper.treeToValue(event.payload, SystemMessage.class);
            if (msg.conversation == null) {
                Logger.warning("onConversationCreate: conv is null. Payload: %s", event.payload);
                return;
            }

            collector.setConvName(msg.conversation.name);

            String text = formatConversation(msg.conversation);
            collector.addSystem(text, msg.time, event.type);
        } catch (Exception e) {
            Logger.exception("onConversationCreate: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void onConversationRename(Collector collector, Event event) {
        try {
            SystemMessage msg = mapper.treeToValue(event.payload, SystemMessage.class);
            collector.setConvName(msg.conversation.name);

            String userName = getUserName(msg.from);
            String text = String.format("**%s** renamed the conversation to **%s**", userName, msg.conversation.name);
            collector.addSystem(text, msg.time, event.type);
        } catch (Exception e) {
            Logger.exception("onConversationRename: conv: %s, msg: %s error: %s", e, event.conversationId, event.messageId, e.getMessage());
        }
    }

    private void testAPI() {
        try {
            api.getSelf();
        } catch (Exception e) {
            Logger.debug("reconnecting... %s", e);
            api = getLHApi();
        }
    }

    private API getLHApi() {
        try {
            LHAccess single = accessDAO.getSingle();

            // if the db is empty just return a dummy API
            if (single == null)
                return new API(httpClient, null, null);

            return new API(httpClient, null, single.token);
        } catch (Exception e) {
            Logger.exception("getLHApi: %s", e, e.getMessage());
            return new API(httpClient, null, null);
        }
    }

    private String formatConversation(Conversation conversation) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**%s** created conversation **%s** with: \n",
                getUserName(conversation.creator),
                conversation.name));
        for (Member member : conversation.members) {
            sb.append(String.format("- **%s** \n", getUserName(member.id)));
        }
        return sb.toString();
    }

    @Nullable
    private String getText(UUID msgId) throws IOException {
        Event event = eventsDAO.get(msgId);
        if (event == null)
            return null;
        TextMessage orgMessage = mapper.treeToValue(event.payload, TextMessage.class);
        return orgMessage.getText();
    }

    @Nullable
    private String getUserName(UUID userId) {
        Cache cache = new Cache(api);
        return cache.getUser(userId).name;
    }

    private Mustache compileTemplate() {
        String path = "templates/conversation.html";
        return mf.compile(path);
    }

    private String execute(Object model) throws IOException {
        Mustache mustache = compileTemplate();
        try (StringWriter sw = new StringWriter()) {
            mustache.execute(new PrintWriter(sw), model).flush();
            return sw.toString();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class _CallingContent {
        @JsonProperty
        boolean resp;
        @JsonProperty
        String sessid;
        @JsonProperty
        String type;
        @JsonProperty
        String version;
    }
}
