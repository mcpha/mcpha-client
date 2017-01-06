/*
 * Copyright 2016 John Preston<byhisdeeds@gmail.com> NURAS.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.nuras.wspha;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.websocket.api.*;
import org.eclipse.jetty.websocket.api.annotations.*;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 *
 * @author John Preston<byhisdeeds@gmail.com>
 */
@WebSocket
public class WebsocketHandler
{
  private String sender, msg;

  @OnWebSocketConnect
  public void onConnect(Session user) throws Exception
  {
    String username = "User" + WSPHA.nextUserNumber++;
    WSPHA.userUsernameMap.put(user, username);
//    WSPHA.broadcastMessage(sender = "Server", msg = (username + " joined the chat"));
System.out.println("onConnect - username="+username);
  }

  @OnWebSocketClose
  public void onClose(Session user, int statusCode, String reason)
  {
    String username = WSPHA.userUsernameMap.get(user);
    WSPHA.userUsernameMap.remove(user);
    WSPHA.broadcastMessage(sender = "Server", msg = (username + " left the chat"));
System.out.println("onColose - username="+username);
  }

  @OnWebSocketMessage
  public void onMessage(Session user, String message)
  {
    // parse json message
    JSONParser parser = new JSONParser();
    try
    {
      WSPHA.sendJSONTextMessage(user.getRemote(), message);
System.out.println("onMessage - sender="+sender+", message="+message);

      String msg;
      JSONObject json = (JSONObject)parser.parse(message);
      String command = json.get("command").toString();
      if (command.equals("connect"))
      {
        String deviceip = json.get("deviceip").toString();
        long port = (long)json.get("port");
        WSPHA.connectToDevice(user, deviceip, (int)port);
      }
      else if (command.equals("disconnect"))
      {
        WSPHA.disconnectFromDevice(user);
      }
      else if (command.equals("set_acquisition_time"))
      {
        long value = (long)json.get("value");
        WSPHA.setAcquisitionTime(user, value);
      }
      else if (command.equals("set_sample_rate"))
      {
        long value = (long)json.get("value");
        WSPHA.mcphaSetSampleRate(value);
      }
      else if (command.equals("set_roi"))
      {
        long roi = (long)json.get("roi");
        long start = (long)json.get("from");
        long end = (long)json.get("to");
        WSPHA.mcphaSetRoi(user, (int)roi, (int)start, (int)end);
      }
    }
    catch (ParseException | IOException ex)
    {
      Logger.getLogger(WebsocketHandler.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

}
