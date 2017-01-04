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

import org.eclipse.jetty.websocket.api.*;

import org.json.*;

import java.text.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

import static j2html.TagCreator.*;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static spark.Spark.*;

/**
 * 
 * @author John Preston<byhisdeeds@gmail.com>
 */
public class WSPHA
{

  public static final Long MCPHA_COMMAND_RESET_TIMER = 0L;

  public static final Long MCPHA_COMMAND_RESET_HISTOGRAM = 1L;

  public static final Long MCPHA_COMMAND_RESET_OSCILLOSCOPE = 2L;

  public static final Long MCPHA_COMMAND_RESET_GENERATOR = 3L;
  
  public static final Long MCPHA_COMMAND_SET_SAMPLE_RATE = 4L;
  
  public static final Long MCPHA_COMMAND_SET_TIMER_VALUE = 11L;
  
  public static final Long MCPHA_COMMAND_SET_TIMER_MODE = 12L;

  // this map is shared between sessions and threads,
  // so it needs to be thread-safe (http://stackoverflow.com/a/2688817)
  static Map<Session, String> userUsernameMap = new ConcurrentHashMap<>();
  
  static Socket deviceSocket = null;
  
  //Assign to username for next connecting user
  static int nextUserNumber = 1;

  /**
   * 
   * @param args 
   */
  public static void main(String[] args)
  {
//    staticFiles.externalLocation("/html5");
    staticFiles.location("/html5"); //index.html is served at localhost:4567 (default port)
    staticFiles.expireTime(600);
    webSocket("/wspha", WebsocketHandler.class);
    init();
  }

  /**
   * Sends a message from one user to all users, along with a list
   * of current usernames
   * 
   * @param sender
   * @param message 
   */
  public static void broadcastMessage(String sender, String message)
  {
    userUsernameMap.keySet().stream().filter(Session::isOpen).forEach(session ->
    {
      try
      {
//        session.getRemote().sendString(String.valueOf(new JSONObject()
//          .put("userMessage", createHtmlMessageFromSender(sender, message))
//          .put("userlist", userUsernameMap.values())
//        ));
        session.getRemote().sendString("sender="+sender+", message="+message);
      }
      catch (Exception e)
      {
        e.printStackTrace();
      }
    });
  }
  
  /**
   * 
   * @param dest
   * @param json
   * @throws IOException 
   */
  public static void sendJSONObjectMessage(RemoteEndpoint dest, JSONObject json)
    throws IOException
  {
    sendJSONTextMessage(dest, json.toString());
  }
  
  /**
   * 
   * @param dest
   * @param json
   * @throws IOException 
   */
  public static void sendJSONTextMessage(RemoteEndpoint dest, String json)
    throws IOException
  {
System.out.println("SEND_MESSAGE:"+json);
    dest.sendString(json);
  }

  /**
   * 
   * @param user
   * @param deviceip
   * @param port 
   */
  synchronized public static void connectToDevice(Session user,
    String deviceip, int port)
  {
    try
    {
      deviceSocket = new Socket();
      deviceSocket.connect(new InetSocketAddress(deviceip,port), 8000);
      
      // initialise device
      mcphaResetTimer(0);
      
      mcphaResetHistogram(0);
      
      mcphaSetSampleRate(4);
      
      JSONObject json = new JSONObject();
      json.put("command", "connect");
      json.put("message", "Connection established");
      json.put("status", 0);
      sendJSONObjectMessage( user.getRemote(), json);
    }
    catch (IOException ex)
    {
      deviceSocket = null;
      try
      {
        JSONObject json = new JSONObject();
        json.put("command", "connect");
        json.put("message", ex.toString());
        json.put("status", 1);
        sendJSONObjectMessage( user.getRemote(), json);
      }
      catch (IOException ex1)
      {
        Logger.getLogger(WSPHA.class.getName()).log(Level.SEVERE, null, ex1);
      }
    }
  }

  /**
   * 
   * @param user
   */
  synchronized public static void disconnectFromDevice(Session user)
  {
    try
    {
      if (deviceSocket == null)
      {
        System.out.println("NO DEVICE CONNECTION");
      }
      else
      {
        deviceSocket.close();
        deviceSocket = null;
        JSONObject json = new JSONObject();
        json.put("command", "disconnect");
        json.put("message", "Device disconnected");
        json.put("status", 0);
        sendJSONObjectMessage(user.getRemote(), json);
      }
    }
    catch (IOException ex)
    {
      deviceSocket = null;
      try
      {
        JSONObject json = new JSONObject();
        json.put("command", "disconnect");
        json.put("message", ex.toString());
        json.put("status", 0);
        sendJSONObjectMessage( user.getRemote(), json);
      }
      catch (IOException ex1)
      {
        Logger.getLogger(WSPHA.class.getName()).log(Level.SEVERE, null, ex1);
      }
    }
  }

  /**
   * 
   * @param user
   * @param value
   */
  synchronized public static void setAcquisitionTime(Session user, long value)
  {
System.out.println("trying to set acquisiton time to "+value);
    try
    {
//      deviceSocket = new Socket();
//      deviceSocket.connect(new InetSocketAddress(deviceip,port), 8000);
      
      JSONObject json = new JSONObject();
      json.put("command", "connect");
      json.put("message", "Successful");
      json.put("status", 0);
      sendJSONObjectMessage( user.getRemote(), json);
    }
    catch (IOException ex)
    {
      deviceSocket = null;
      try
      {
        JSONObject json = new JSONObject();
        json.put("command", "connect");
        json.put("message", ex.toString());
        json.put("status", 0);
        sendJSONObjectMessage( user.getRemote(), json);
      }
      catch (IOException ex1)
      {
        Logger.getLogger(WSPHA.class.getName()).log(Level.SEVERE, null, ex1);
      }
    }
  }

  /**
   * Set sample rate
   * 
   * @param rate 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetSampleRate(long rate)
    throws IOException
  {
    if (rate < 4)
    {
      rate = 4;
    }
    
    DataOutputStream dos = new DataOutputStream(deviceSocket.getOutputStream());
    long b = (long)(MCPHA_COMMAND_SET_SAMPLE_RATE << 56) | (0L << 52) | rate;
    dos.writeLong(Long.reverseBytes(b));
  }

  /**
   * Reset timer
   * 
   * @param chan 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaResetTimer(long chan)
    throws IOException
  {
    DataOutputStream dos = new DataOutputStream(deviceSocket.getOutputStream());
    long b = (long)(MCPHA_COMMAND_RESET_TIMER << 56) | (validateChannel(chan) << 52) | 0L;
    dos.writeLong(Long.reverseBytes(b));
  }

  /**
   * Reset timer
   * 
   * @param chan 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaResetHistogram(long chan)
    throws IOException
  {
    DataOutputStream dos = new DataOutputStream(deviceSocket.getOutputStream());
    long b = (long)(MCPHA_COMMAND_RESET_HISTOGRAM << 56) | (validateChannel(chan) << 52) | 0L;
    dos.writeLong(Long.reverseBytes(b));
  }

  /**
   * Reset oscilloscope
   * 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaResetOscilloscope()
    throws IOException
  {
    DataOutputStream dos = new DataOutputStream(deviceSocket.getOutputStream());
    long b = (long)(MCPHA_COMMAND_RESET_OSCILLOSCOPE << 56) | (0L << 52) | 0L;
    dos.writeLong(Long.reverseBytes(b));
  }

  /**
   * Reset generator
   * 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaResetGenerator()
    throws IOException
  {
    DataOutputStream dos = new DataOutputStream(deviceSocket.getOutputStream());
    long b = (long)(MCPHA_COMMAND_RESET_GENERATOR << 56) | (0L << 52) | 0L;
    dos.writeLong(Long.reverseBytes(b));
  }

  /**
   * Set timer value
   * 
   * @param chan
   * @param value
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetTimerValue(long chan, long value)
    throws IOException
  {
    DataOutputStream dos = new DataOutputStream(deviceSocket.getOutputStream());
    long b = (long)(MCPHA_COMMAND_SET_TIMER_VALUE << 56) | (validateChannel(chan)  << 52) | value;
    dos.writeLong(Long.reverseBytes(b));
  }

  /**
   * Set timer mode
   * 
   * @param chan
   * @param value
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetTimerMode(long chan, long value)
    throws IOException
  {
    DataOutputStream dos = new DataOutputStream(deviceSocket.getOutputStream());
    long b = (long)(MCPHA_COMMAND_SET_TIMER_MODE << 56) | (validateChannel(chan)  << 52) | value;
    dos.writeLong(Long.reverseBytes(b));
  }

  /**
   * 
   * @param chan
   * @return 
   */
  private static long validateChannel(long chan)
  {
    if (chan < 0)
    {
      return 0;
    } else if (chan > 1)
    {
      return 1;
    }
    
    return chan;
  }
  
  //Builds a HTML element with a sender-name, a message, and a timestamp,
  private static String createHtmlMessageFromSender(String sender, String message)
  {
    return article().with(
      b(sender + " says:"),
      p(message),
      span().withClass("timestamp").withText(new SimpleDateFormat("HH:mm:ss").format(new Date()))
    ).render();
  }

}
