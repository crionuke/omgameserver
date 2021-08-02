package com.crionuke.omgameserver.runtime.lua.events;

import org.luaj.vm2.LuaValue;

/**
 * @author Kirill Byvshev (k@byv.sh)
 * @version 1.0.0
 */
public class LuaMessageReceivedEvent extends LuaEvent {
    static public final String MESSAGE_RECEIVED_EVENT_ID = "message_received";

    public LuaMessageReceivedEvent(long clientId, LuaValue luaValue) {
        super(MESSAGE_RECEIVED_EVENT_ID);
        set("client_id", clientId);
        set("message", luaValue);
    }
}
