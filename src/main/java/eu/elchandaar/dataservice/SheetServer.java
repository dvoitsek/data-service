/*
* To change this license header, choose License Headers in Project Properties.
* To change this template file, choose Tools | Templates
* and open the template in the editor.
*/
package eu.elchandaar.dataservice;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import org.apache.commons.io.FileUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author lancelot
 */
@ServerEndpoint("/sheets")
public class SheetServer {
    private static final Logger LOG = LoggerFactory.getLogger(SheetServer.class);
    private static final String characterDir = "/apps/service/elchandaar/sheets/";
    private static final JSONParser parser = new JSONParser();
    
    private static final Map<String, Session> idToSession = new HashMap<>();
    private static final Lock lock = new ReentrantLock();
    
    @OnOpen
    public void onOpen(Session session) throws IOException {
        LOG.info("New connection " + session.getId());
        try {
            lock.lock();
            idToSession.put(session.getId(), session);
        } finally {
            lock.unlock();
        }
        JSONObject resp = new JSONObject();
        resp.put("action", "list");
        resp.put("data", this.getAvailableSheets());
        session.getBasicRemote().sendText(resp.toString());
    }
    
    @OnMessage
    public void onMessage(String message, Session session) throws ParseException, IOException {
        LOG.info("Message from " + session.getId() + " : " + message);
        
        JSONObject jMsg = (JSONObject) parser.parse(message);
        String action = (String) jMsg.get("action");
        switch(action) {
            case "subscribe":
                // Subscribe user to existing character file, send character
                String character = (String) jMsg.get("name");
                JSONObject resp = this.subscribe(session.getId(), character);
                session.getBasicRemote().sendText(resp.toString());
                return;
            case "create":
                // Create new character file, subscribe user to it, send character to user, update list with all users
                character = (String) jMsg.get("name");
                JSONObject data = (JSONObject) jMsg.get("data");
                JSONObject newChar = this.create(session.getId(), character, data.toString());
                session.getBasicRemote().sendText(newChar.toString());
                
                resp = new JSONObject();
                resp.put("action", "list");
                resp.put("data", this.getAvailableSheets());
                this.broadcast(resp);
                break;
            case "update":
                // Save received data, update all users
                character = (String) jMsg.get("name");
                data = (JSONObject) jMsg.get("data");
                resp = this.update(character, data.toString());
                this.broadcast(resp);
                break;
        }
    }
    
    @OnClose
    public void onClose(Session session) {
        LOG.info("Session closed " + session.getId());
        
        try {
            lock.lock();
            idToSession.remove(session.getId());
        }
        finally {
            lock.unlock();
        }
    }
    
    private JSONObject subscribe(String sessionId, String character) throws IOException, ParseException { 
        File fSheet = new File(characterDir + character);
        String sheet = FileUtils.readFileToString(fSheet);
        JSONObject jSheet = (JSONObject) parser.parse(sheet);
        
        JSONObject ret = new JSONObject();
        ret.put("action", "character-data");
        ret.put("data", jSheet);
        
        return ret;
    }
    
    private JSONObject create(String sessionId, String characterName, String characterData) throws IOException, ParseException {
        File nChar = new File(characterDir + characterName);
        FileUtils.touch(nChar);
        FileUtils.writeStringToFile(nChar, characterData);
        
        JSONObject data = (JSONObject) parser.parse(characterData);
        JSONObject ret = new JSONObject();
        ret.put("action", "character-data");
        ret.put("data", data);
        return ret;
    }
    
    private JSONObject update(String name, String data) throws IOException, ParseException {
        File nChar = new File(characterDir + name);
        try {
            FileUtils.writeStringToFile(nChar, data);
        } catch (IOException ex) {
            data = FileUtils.readFileToString(nChar);
        }
        
        JSONObject jSheet = (JSONObject) parser.parse(data);
        JSONObject ret = new JSONObject();
        ret.put("action", "character-data");
        ret.put("data", jSheet);
        return ret;
    }
    
    private void broadcast(JSONObject message) throws IOException {
        try {
            lock.lock();
            Set<String> ids = idToSession.keySet();
            for(String id: ids) {
                Session s = idToSession.get(id);
                if(s.isOpen()) {
                    s.getBasicRemote().sendText(message.toString());
                } else {
                    idToSession.remove(id);
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    private JSONArray getAvailableSheets() {
        JSONArray ret = new JSONArray();
        
        File dir = new File(characterDir);
        File[] fSheets = dir.listFiles();
        for(File s: fSheets) {
            ret.add(s.getName());
        }
        return ret;
    }
}
