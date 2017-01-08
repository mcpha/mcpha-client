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
package org.nuras.mcpha;

import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.Timer;

import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import static spark.Spark.*;

/**
 * 
 * @author John Preston<byhisdeeds@gmail.com>
 */
public class Client
{
  static class ROI
  {
    public int start = 0;
    public int end = 0;
    public long counts = -1;
  }
  
  public static final long TIMER_FREQ = 125000000L;
  
  public static final double TIME_PER_TICK = 1.0 / (double)TIMER_FREQ;
  
  private static final int SHIFT_CODE = 56;
  
  private static final int SHIFT_CHAN = 52;
  
  public static final Long MCPHA_COMMAND_RESET_TIMER = 0L;

  public static final Long MCPHA_COMMAND_RESET_HISTOGRAM = 1L;

  public static final Long MCPHA_COMMAND_RESET_OSCILLOSCOPE = 2L;

  public static final Long MCPHA_COMMAND_RESET_GENERATOR = 3L;
  
  public static final Long MCPHA_COMMAND_SET_SAMPLE_RATE = 4L;
  
  public static final Long MCPHA_COMMAND_SET_NEGATOR_MODE = 5L;
  
  public static final Long MCPHA_COMMAND_SET_BASELINE_MODE = 6L;
  
  public static final Long MCPHA_COMMAND_SET_BASELINE_LEVEL = 7L;
  
  public static final Long MCPHA_COMMAND_SET_PHA_DELAY = 8L;
  
  public static final Long MCPHA_COMMAND_SET_PHA_MIN_THRESHOLD = 9L;
  
  public static final Long MCPHA_COMMAND_SET_PHA_MAX_THRESHOLD = 10L;
  
  public static final Long MCPHA_COMMAND_SET_TIMER_VALUE = 11L;
  
  public static final Long MCPHA_COMMAND_SET_TIMER_MODE = 12L;
  
  public static final Long MCPHA_COMMAND_READ_TIMER = 13L;
  
  public static final Long MCPHA_COMMAND_READ_HISTOGRAM = 14L;

  // this map is shared between sessions and threads,
  // so it needs to be thread-safe (http://stackoverflow.com/a/2688817)
  static Map<Session, String> userUsernameMap = new ConcurrentHashMap<>();
  
  static Socket deviceSocket = null;
  
  static IntBuffer histogram_data = null;
  
  static boolean acquisition_state_active = false;
  
  //Assign to username for next connecting user
  static int nextUserNumber = 1;
  
  static ROI[] rois = new ROI[]{new ROI(), new ROI(), new ROI()};

  static AcquisitionUpdateTask acquisitionUpdateTask = null;
  
  static Timer acquisitionMonitorTimer = null;
  
  static long aquisitionTime = 0L;
  
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
   * 
   * @param user 
   * @param chan 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaStartAcquisition(Session user, int chan)
    throws IOException
  {
    // if device not connected then return
    if (deviceSocket == null || !deviceSocket.isConnected())
    {
      return;
    }

    // start acquisition
    // device inactive
    mcphaSetAquisitionState(user, chan, 1L);
    
    if (acquisitionUpdateTask != null)
    {
      acquisitionUpdateTask.cancel();
    }
    
    // inistantiate new task
    acquisitionUpdateTask = new AcquisitionUpdateTask(user, chan);
    
    // cancel any existing timer tasks.
    if (acquisitionMonitorTimer != null)
    {
      acquisitionMonitorTimer.cancel();
    }
    
    // create new timer task
    acquisitionMonitorTimer = new Timer();
    acquisitionMonitorTimer.scheduleAtFixedRate(
      acquisitionUpdateTask, 0, 2000);
  }

  /**
   * 
   * @param user 
   * @param chan 
   * @throws java.io.IOException 
   */
  public static void mcphaStopAcquisition(Session user, int chan)
    throws IOException
  {
    // if device not connected then return
    if (deviceSocket == null || !deviceSocket.isConnected())
    {
      return;
    }

    // stop data acquisition
    // device inactive
    mcphaSetAquisitionState(user, chan, 0L);
    
    if (acquisitionUpdateTask != null)
    {
      acquisitionUpdateTask.cancel();
    }
    
    // cancel any existing timer tasks.
    if (acquisitionMonitorTimer != null)
    {
      acquisitionMonitorTimer.cancel();
    }
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
      catch (IOException e)
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
   * @return 
   */
  private static JSONObject createJSONResponseObject()
  {
    JSONObject json = new JSONObject();
    json.put("type", "resp");
    
    return json;
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
      if (deviceSocket == null || !deviceSocket.isConnected())
      {
        deviceSocket = new Socket();
        deviceSocket.connect(new InetSocketAddress(deviceip,port), 8000);
        deviceSocket.setSoTimeout(60000);
      }

      JSONObject json = createJSONResponseObject();
      json.put("command", "connect");
      json.put("message", "Connection established");
      json.put("status", 0);
      sendJSONObjectMessage( user.getRemote(), json);

      
      // initialise device
      mcphaSetSampleRate(4L);
      mcphaSetPhaDelay(0L, 100L);
      mcphaSetPhaMinThreshold(0L, 300L);
      mcphaSetPhaMaxThreshold(0L, 16300L);
//      mcphaResetHistogram(0);
      
      // get histgram data
      getHistogramData(user, 0);
    }
    catch (IOException ex)
    {
      if (deviceSocket != null)
      {
        try
        {
          deviceSocket.close();
        }
        catch (IOException ex1)
        {
          Logger.getLogger(Client.class.getName()).log(Level.SEVERE, null, ex1);
        }
      }
      deviceSocket = null;
      try
      {
        JSONObject json = createJSONResponseObject();
        json.put("command", "connect");
        json.put("message", ex.toString());
        json.put("status", 1);
        sendJSONObjectMessage( user.getRemote(), json);
      }
      catch (IOException ex1)
      {
        Logger.getLogger(Client.class.getName()).log(Level.SEVERE, null, ex1);
      }
    }
  }

  /**
   * Close socket and disconnect from Red Pitaya device.
   * 
   * @param user
   * @throws java.io.IOException
   */
  synchronized public static void disconnectFromDevice(Session user)
    throws IOException
  {
    JSONObject json = createJSONResponseObject();
    json.put("command", "disconnect");
    json.put("status", 0);

    if (deviceSocket == null)
    {
      json.put("message", "Nothing to do. Device not connected");
    }
    else
    {
      deviceSocket.close();
      deviceSocket = null;
      json.put("message", "Device disconnected");
    }
    
    sendJSONObjectMessage(user.getRemote(), json);
  }

  /**
   * Start/Stop data acquisition of the connected device. When state is 0
   * then the acquisition is stopped, and when it is 1 then it is active.
   * 
   * @param user 
   * @param chan 
   * @param state 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetAquisitionState(Session user, int chan, long state)
    throws IOException
  {
    if (deviceSocket != null && deviceSocket.isConnected())
    {
      mcphaSetTimerMode(chan, state);
      
      acquisition_state_active = state == 1;
      JSONObject json = createJSONResponseObject();
      json.put("command", "set_acquisition_state");
      json.put("message", "");
      json.put("state", acquisition_state_active ? "active" : "inactive");
      json.put("status", 0);
      sendJSONObjectMessage(user.getRemote(), json);
    }
  }

  /**
   * 
   * @param user 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaGetAquisitionState(Session user)
    throws IOException
  {
    if (deviceSocket != null)
    {
      JSONObject json = createJSONResponseObject();
      json.put("command", "get_acquisition_state");
      json.put("message", "");
      json.put("state", acquisition_state_active ? "active" : "inactive");
      json.put("status", 0);
      sendJSONObjectMessage(user.getRemote(), json);
    }
  }

  /**
   * 
   * @param user
   * @param chan
   * @throws IOException 
   */
  synchronized public static void getHistogramData(Session user, long chan)
    throws IOException
  {
    // get elapsed time
    double t = mcphaGetTimerValue(chan);

    // get histogram data
    histogram_data = mcphaGetHistogram(chan);

    // push data
    JSONObject json = createJSONResponseObject();
    json.put("command", "get_histogram_data");
    json.put("message", "");
    json.put("status", 0);
    json.put("timer", String.format("%.2f", t));
    json.put("label", "histogram");

    JSONArray arr = new JSONArray();
    for (int i=0; i<histogram_data.capacity(); i++)
    {
      JSONArray xy = new JSONArray();
      xy.put(i).put(histogram_data.get(i));
      arr.put(xy);
    }
    json.put("data", arr);

    for (int i=1; i<=3; i++)
    {
      JSONObject roi = getRoiJSONObject(1);
      if (roi != null)
      {
        json.put("roi"+i, roi);
      }
    }
        
    sendJSONObjectMessage(user.getRemote(), json);
  }

  /**
   * 
   * @param roi
   * @return 
   */
  synchronized public static JSONObject getRoiJSONObject(int roi)
  {
    JSONObject json = null;
    
    if (histogram_data != null && rois[roi-1].counts != -1)
    {
      long counts = 0;
      
      JSONArray data = new JSONArray();
      for (int i=rois[roi-1].start; i<=rois[roi-1].end; i++)
      {
        JSONArray o = new JSONArray();
        int y = histogram_data.get(i);
        o.put(i).put(y);
        data.put(o);
        counts += y;
      }

      json = new JSONObject();
      json.put("label", "ROI #"+roi);
      json.put("counts", counts);
      json.put("start", rois[roi-1].start);
      json.put("end", rois[roi-1].end);
      json.put("data", data);
    }
    
    return json;
  }
  
  /**
   * 
   * @param user
   * @param value
   */
  synchronized public static void setAcquisitionTime(Session user, long value)
  {
    try
    {
      JSONObject json = createJSONResponseObject();
      json.put("command", "connect");
      json.put("message", "Successful");
      json.put("status", 0);
      sendJSONObjectMessage(user.getRemote(), json);
    }
    catch (IOException ex)
    {
      deviceSocket = null;
      try
      {
        JSONObject json = createJSONResponseObject();
        json.put("command", "connect");
        json.put("message", ex.toString());
        json.put("status", 1);
        sendJSONObjectMessage( user.getRemote(), json);
      }
      catch (IOException ex1)
      {
        Logger.getLogger(Client.class.getName()).log(Level.SEVERE, null, ex1);
      }
    }
  }

  /**
   * 
   * @param user
   * @param roi
   * @param start
   * @param end
   * @throws IOException 
   */
  synchronized public static void mcphaSetRoi(Session user, int roi, int start, int end)
    throws IOException
  {
    ROI r = null;
    
    if (roi < 1 || roi > 3)
    {
      JSONObject json = createJSONResponseObject();
      json.put("command", "connect");
      json.put("message", String.format("ROI number [%d] outside range of 1 to 3.", roi));
      json.put("status", 1);
      sendJSONObjectMessage( user.getRemote(), json);
    }
    
    rois[roi-1].start = start;
    rois[roi-1].end = end;
    rois[roi-1].counts = 0L;

    JSONObject json = getRoiJSONObject(roi);
    if (json != null)
    {
      JSONObject o = createJSONResponseObject();
      o.put("command", "get_roi_data");
      o.put("message", "");
      o.put("roi"+roi, json);
      o.put("status", 0);
      sendJSONObjectMessage(user.getRemote(), o);
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
    
    sendCommand(MCPHA_COMMAND_SET_SAMPLE_RATE, 0L, rate);
  }

  /**
   * Reset histogram
   * 
   * @param chan 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaResetHistogram(long chan)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_RESET_HISTOGRAM, chan, 0L);
  }

  /**
   * Reset oscilloscope
   * 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaResetOscilloscope()
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_RESET_OSCILLOSCOPE, 0L, 0L);
  }

  /**
   * Reset generator
   * 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaResetGenerator()
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_RESET_GENERATOR, 0L, 0L);
  }

  /**
   * Set negator mode, 0 for disabled and 1 for enabled
   * 
   * @param chan
   * @param mode
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetNegatorMode(long chan, long mode)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_SET_NEGATOR_MODE, chan, mode);
  }

  /**
   * Set baseline mode, 0 for none and 1 for auto
   * 
   * @param chan
   * @param mode
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetBaselineMode(long chan, long mode)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_SET_BASELINE_MODE, chan, mode);
  }

  /**
   * Set baseline level
   * 
   * @param chan
   * @param level
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetBaselineLevel(long chan, long level)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_SET_BASELINE_LEVEL, chan, level);
  }

  /**
   * Set PHA delay
   * 
   * @param chan
   * @param delay
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetPhaDelay(long chan, long delay)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_SET_PHA_DELAY, chan, delay);
  }

  /**
   * Set PHA min threshold
   * 
   * @param chan
   * @param threshold
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetPhaMinThreshold(long chan, long threshold)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_SET_PHA_MIN_THRESHOLD, chan, threshold);
  }

  /**
   * Set PHA max threshold
   * 
   * @param chan
   * @param threshold
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetPhaMaxThreshold(long chan, long threshold)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_SET_PHA_MAX_THRESHOLD, chan, threshold);
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
    sendCommand(MCPHA_COMMAND_RESET_TIMER, chan, 0L);
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
    sendCommand(MCPHA_COMMAND_SET_TIMER_VALUE, chan, value);
  }

  /**
   * Set timer mode, 0 for stop and 1 for running
   * 
   * @param chan
   * @param mode
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetTimerMode(long chan, long mode)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_SET_TIMER_MODE, chan, mode);
  }

  /**
   * Get timer value in seconds which is derived from the 64-bit unsigned
   * integer value returned from the server, that is the number of counts
   * at 125MHz from the start of the acquisition.
   * 
   * @param chan
   * @return the timer value in seconds since the start of acquisition
   * @throws java.io.IOException 
   */
  synchronized public static double mcphaGetTimerValue(long chan)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_READ_TIMER, chan, 0L);
    
    // read response
    DataInputStream in = new DataInputStream(deviceSocket.getInputStream());
    
    long number  = Long.reverseBytes(in.readLong());

    return (double)number * TIME_PER_TICK; 
  }

  /**
   * Get histogram
   * 
   * @param chan
   * @return 
   * @throws java.io.IOException 
   */
  synchronized public static IntBuffer mcphaGetHistogram(long chan)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_READ_HISTOGRAM, chan, 0);

    DataInputStream in = new DataInputStream(deviceSocket.getInputStream());
    
    ByteBuffer data = ByteBuffer.allocate(65536);
    data.order(ByteOrder.nativeOrder());
//    byte[] b = new byte[65536];
    in.readFully(data.array());
    
////    for (int i=1; i<b.length; i++)
////    {
////      System.out.format("[%d=%d]", i, (int)b[i]);
////      if (i%10 == 0)
////      {
////        System.out.println();
////      }
////    }
    
//    ByteBuffer wrapped = ByteBuffer.wrap(b); // big-endian by default
//    wrapped.order(ByteOrder.nativeOrder());
    
//    IntBuffer ib = wrapped.asIntBuffer();
//    int[] data = new int[16301];
    
//    for (int i=0; i<data.length; i++)
//    {
//      data[i+1] = ib.get();
//    }
    
//    return data;
    return data.asIntBuffer();
  }

  /**
   * Send command to device
   * 
   * @param code
   * @param chan
   * @param data 
   */
  private static void sendCommand(long code, long chan, long data)
    throws IOException
  {
System.out.println(">>>>>>>>>>>>> code="+code+", chan="+chan+", data="+data);
    DataOutputStream out = new DataOutputStream(deviceSocket.getOutputStream());
    long b = (long)(code << SHIFT_CODE) | (validateChannel(chan)  << SHIFT_CHAN) | data;
    out.writeLong(Long.reverseBytes(b));
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
}
