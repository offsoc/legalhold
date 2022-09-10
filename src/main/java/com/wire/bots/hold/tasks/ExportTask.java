package com.wire.bots.hold.tasks;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wire.bots.hold.DAO.AccessDAO;
import com.wire.bots.hold.DAO.EventsDAO;
import com.wire.bots.hold.model.Event;
import com.wire.bots.hold.model.LHAccess;
import com.wire.bots.hold.utils.Cache;
import com.wire.helium.API;
import com.wire.xenon.backend.models.Conversation;
import com.wire.xenon.backend.models.Member;
import com.wire.xenon.backend.models.SystemMessage;
import com.wire.xenon.backend.models.User;
import com.wire.xenon.models.TextMessage;
import com.wire.xenon.tools.Logger;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.servlets.tasks.Task;
import org.jdbi.v3.core.Jdbi;

import javax.ws.rs.client.Client;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ExportTask extends Task {
    private final Client httpClient;
    private final LifecycleEnvironment lifecycleEnvironment;
    private final EventsDAO eventsDAO;
    private final AccessDAO accessDAO;
    private final ObjectMapper mapper = new ObjectMapper();
    private Cache cache;

    public ExportTask(Jdbi jdbi, Client httpClient, LifecycleEnvironment lifecycle) {
        super("kibana");
        this.httpClient = httpClient;

        lifecycleEnvironment = lifecycle;
        eventsDAO = jdbi.onDemand(EventsDAO.class);
        accessDAO = jdbi.onDemand(AccessDAO.class);
    }

    @Override
    public void execute(Map<String, List<String>> parameters, PrintWriter output) {
        lifecycleEnvironment.scheduledExecutorService("ExportTask")
                .threads(1)
                .build()
                .schedule(this::export, 1, TimeUnit.SECONDS);

        output.println("Kibana task has been queued");
    }

    void export() {
        int count = 0;

        List<Event> events = eventsDAO.getUnexportedConvs();
        Logger.info("Exporting %d conversations to Kibana", events.size());

        for (Event e : events) {
            String name = null;
            List<User> participants = new ArrayList<>();
            Set<UUID> uniques = new HashSet<>();

            List<Event> messages = eventsDAO.listAllUnxported(e.conversationId);

            final LHAccess access = accessDAO.getSingle();
            final API api = new API(httpClient, e.conversationId, access.token);

            cache = new Cache(api, null);

            for (Event event : messages) {
                try {
                    switch (event.type) {
                        case "conversation.create": {
                            SystemMessage msg = mapper.readValue(event.payload, SystemMessage.class);

                            //TODO: only while testing
                            if (msg.conversation == null) {
                                eventsDAO.delete(event.eventId);
                                continue;
                            }

                            if (!uniques.add(msg.id))
                                continue;

                            name = msg.conversation.name;

                            for (Member m : msg.conversation.members) {
                                User user = cache.getUser(m.id);
                                participants.add(user);
                            }

                            String text = format(msg.conversation);

                            log(name, participants, msg, text);

                            if (eventsDAO.markExported(event.eventId) > 0)
                                count++;
                        }
                        break;
                        case "conversation.member-join": {
                            SystemMessage msg = mapper.readValue(event.payload, SystemMessage.class);
                            if (!uniques.add(msg.id))
                                continue;

                            StringBuilder sb = new StringBuilder();
                            sb.append(String.format("%s added these participants: ", name(msg.from)));
                            for (UUID userId : msg.users) {
                                User user = cache.getUser(userId);
                                participants.add(user);

                                sb.append(String.format("%s,", name(userId)));
                            }

                            log(name, participants, msg, sb.toString());

                            if (eventsDAO.markExported(event.eventId) > 0)
                                count++;
                        }
                        case "conversation.member-leave": {
                            SystemMessage msg = mapper.readValue(event.payload, SystemMessage.class);
                            if (!uniques.add(msg.id))
                                continue;

                            StringBuilder sb = new StringBuilder();
                            sb.append(String.format("%s removed these participants: ", name(msg.from)));
                            for (UUID userId : msg.users) {
                                participants.removeIf(x -> x.id.equals(userId));

                                sb.append(String.format("%s,", name(userId)));
                            }

                            log(name, participants, msg, sb.toString());

                            if (eventsDAO.markExported(event.eventId) > 0)
                                count++;
                        }
                        break;
                        case "conversation.otr-message-add.new-text": {
                            TextMessage msg = mapper.readValue(event.payload, TextMessage.class);
                            if (!uniques.add(msg.getMessageId()))
                                continue;

                            log(name, participants, msg);

                            if (eventsDAO.markExported(event.eventId) > 0)
                                count++;
                        }
                        break;
                    }
                } catch (Exception ex) {
                    Logger.exception(ex, "Export exception %s %s", event.conversationId, event.eventId);
                }
            }
        }
        Logger.info("Finished exporting %d messages to Kibana", count);
    }

    private void log(String conversation, List<User> participants, TextMessage msg) throws JsonProcessingException {
        Kibana kibana = new Kibana();
        kibana.type = "text";
        kibana.conversationID = msg.getConversationId();
        kibana.conversationName = conversation;
        kibana.participants = participants.stream()
                .map(x -> x.handle != null ? x.handle : x.id.toString())
                .collect(Collectors.toList());
        kibana.messageID = msg.getMessageId();
        kibana.sender = name(msg.getUserId());
        kibana.text = msg.getText();
        kibana.sent = msg.getTime();

        System.out.println(mapper.writeValueAsString(kibana));
    }

    private void log(String conversation, List<User> participants, SystemMessage msg, String text) throws JsonProcessingException {
        Kibana kibana = new Kibana();
        kibana.type = "system";
        kibana.conversationID = msg.convId;
        kibana.conversationName = conversation;
        kibana.participants = participants.stream()
                .map(x -> x.handle != null ? x.handle : x.id.toString())
                .collect(Collectors.toList());
        kibana.messageID = msg.id;
        kibana.sender = name(msg.from);
        kibana.text = text;
        kibana.sent = msg.time;

        System.out.println(mapper.writeValueAsString(kibana));
    }

    private String format(Conversation conversation) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s created conversation '%s' with: ",
                name(conversation.creator),
                conversation.name));
        for (Member member : conversation.members) {
            sb.append(String.format("%s,", name(member.id)));
        }
        return sb.toString();
    }

    private String name(UUID userId) {
        User user = cache.getUser(userId);
        return user.handle != null ? user.handle : user.id.toString();
    }

    static class Kibana {
        public String type;
        public UUID conversationID;
        public String conversationName;
        public List<String> participants;
        @JsonProperty("sent_on")
        public String sent;
        public String sender;
        public UUID messageID;
        public String text;
    }
}
