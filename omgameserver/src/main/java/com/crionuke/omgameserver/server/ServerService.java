package com.crionuke.omgameserver.server;

import com.crionuke.omgameserver.core.Address;
import com.crionuke.omgameserver.core.Handler;
import com.crionuke.omgameserver.runtime.RuntimeDispatcher;
import com.crionuke.omgameserver.runtime.events.*;
import com.crionuke.omgameserver.websocket.WebSocketDispatcher;
import com.crionuke.omgameserver.websocket.events.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import org.jboss.logging.Logger;
import org.luaj.vm2.LuaValue;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.websocket.Session;
import java.io.IOException;

/**
 * @author Kirill Byvshev (k@byv.sh)
 * @version 1.0.0
 */
@Startup
@ApplicationScoped
public class ServerService extends Handler {
    static final Logger LOG = Logger.getLogger(ServerService.class);

    final WebSocketDispatcher webSocketDispatcher;
    final RuntimeDispatcher runtimeDispatcher;
    final ObjectMapper objectMapper;
    final WebSocketClientTable clientTable;

    ServerService(WebSocketDispatcher webSocketDispatcher, RuntimeDispatcher runtimeDispatcher,
                  ObjectMapper objectMapper) {
        super(ServerService.class.getSimpleName());
        this.webSocketDispatcher = webSocketDispatcher;
        this.runtimeDispatcher = runtimeDispatcher;
        this.objectMapper = objectMapper;
        clientTable = new WebSocketClientTable();
        LOG.infof("Created");
    }

    @PostConstruct
    void postConstruct() {
        Multi<WebSocketEvent> webSocketEvents = webSocketDispatcher.getMulti()
                .emitOn(getSelfExecutor());
        webSocketEvents.filter(event -> event instanceof WebSocketSessionOpenedEvent)
                .onItem().castTo(WebSocketSessionOpenedEvent.class).log().subscribe().with(event -> handleWebSocketSessionOpenedEvent(event));
        webSocketEvents.filter(event -> event instanceof WebSocketMessageReceivedEvent)
                .onItem().castTo(WebSocketMessageReceivedEvent.class).log().subscribe().with(event -> handleWebSocketMessageReceivedEvent(event));
        webSocketEvents.filter(event -> event instanceof WebSocketSessionFailedEvent)
                .onItem().castTo(WebSocketSessionFailedEvent.class).log().subscribe().with(event -> handleWebSocketSessionFailedEvent(event));
        webSocketEvents.filter(event -> event instanceof WebSocketSessionClosedEvent)
                .onItem().castTo(WebSocketSessionClosedEvent.class).log().subscribe().with(event -> handleWebSocketSessionClosedEvent(event));

        Multi<RuntimeEvent> runtimeEvents = runtimeDispatcher.getMulti()
                .emitOn(getSelfExecutor());
        runtimeEvents.filter(event -> event instanceof SendEvent)
                .onItem().castTo(SendEvent.class).log().subscribe().with(event -> handleSendEvent(event));
    }

    void handleWebSocketSessionOpenedEvent(WebSocketSessionOpenedEvent event) {
        Session session = event.getSession();
        WebSocketClient webSocketClient = new WebSocketClient(session);
        clientTable.put(webSocketClient);
        LOG.infof("WebSocket client created, webSocketClient=%s", webSocketClient);
        Address address = event.getAddress();
        runtimeDispatcher.fire(new ClientCreatedEvent(webSocketClient.getId(), address));
    }

    void handleWebSocketMessageReceivedEvent(WebSocketMessageReceivedEvent event) {
        Session session = event.getSession();
        if (clientTable.contain(session)) {
            WebSocketClient client = clientTable.get(session);
            Address address = event.getAddress();
            String message = event.getMessage();
            try {
                LuaValue luaValue = objectMapper.readValue(message, LuaValue.class);
                runtimeDispatcher.fire(new MessageReceivedEvent(client.getId(), address, luaValue));
            } catch (IOException e) {
                LOG.debugf("Wrong json message, client=%s", client);
            }

        } else {
            LOG.infof("WebSocket client not found, session=%s", session);
        }
    }

    void handleWebSocketSessionFailedEvent(WebSocketSessionFailedEvent event) {
        Session session = event.getSession();
        if (clientTable.contain(session)) {
            WebSocketClient client = clientTable.get(session);
            clientTable.remove(client);
            Address address = event.getAddress();
            LOG.infof("WebSocket client removed, client=%s, address=%s", client, address);
            runtimeDispatcher.fire(new ClientRemovedEvent(client.getId(), address));
        }
    }

    void handleWebSocketSessionClosedEvent(WebSocketSessionClosedEvent event) {
        Session session = event.getSession();
        if (clientTable.contain(session)) {
            WebSocketClient client = clientTable.get(session);
            clientTable.remove(client);
            Address address = event.getAddress();
            LOG.infof("WebSocket client removed, client=%s, address=%s", client, address);
            runtimeDispatcher.fire(new ClientRemovedEvent(client.getId(), address));
        }
    }

    void handleSendEvent(SendEvent event) {
        long clientId = event.getClientId();
        if (clientTable.contain(clientId)) {
            WebSocketClient client = clientTable.get(clientId);
            Session session = client.getSession();
            try {
                String message = objectMapper.writeValueAsString(event.getLuaValue());
                session.getAsyncRemote().sendText(message);
            } catch (JsonProcessingException e) {
                LOG.infof("Json serialization failed, %s", e.getMessage());
            }
        }
    }
}
