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
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.Timer;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

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
  
  public static final Long MCPHA_COMMAND_READ_HISTOGRAM_DATA = 14L;
  
  public static final Long MCPHA_COMMAND_SET_NUMBER_OF_SAMPLES_BEFORE_TRIGGER = 19L;

  public static final Long MCPHA_COMMAND_SET_TOTAL_NUMBER_OF_SAMPLES_TO_ACQUIRE = 20L;

  public static final Long MCPHA_COMMAND_START_OSCILLOSCOPE = 21L;

  public static final Long MCPHA_COMMAND_READ_OSCILLOSCOPE_STATUS = 22L;

  public static final Long MCPHA_COMMAND_READ_OSCILLOSCOPE_DATA = 23L;

  // this map is shared between sessions and threads,
  // so it needs to be thread-safe (http://stackoverflow.com/a/2688817)
  static Map<Session, String> userUsernameMap = new ConcurrentHashMap<>();
  
  static Socket deviceSocket = null;
  
  static IntBuffer histogram_data = null;
  
  static boolean acquisition_state_active = false;
  
  // Assign to username for next connecting user
  static int nextUserNumber = 1;
  
  static ROI[] rois = new ROI[]{new ROI(), new ROI(), new ROI()};

  static AcquisitionUpdateTask acquisitionUpdateTask = null;
  
  static Timer acquisitionMonitorTimer = null;
  
  static long aquisitionTime = 0L;
  
  static boolean debug = false;
  
  /**
   * 
   * @param args 
   * @throws org.apache.commons.cli.ParseException 
   */
  public static void main(String[] args)
    throws ParseException
  {
    Option helpOption = Option.builder("h")
                              .longOpt("help")
                              .required(false)
                              .desc("shows this message")
                              .build();

    Option debugOption = Option.builder("d")
                               .longOpt("debug")
                               .required(false)
                               .desc("show debug messages")
                               .build();

    Option wsurlOption = Option.builder("u")
                               .longOpt("wsurl")
                               .numberOfArgs(1)
                               .required(false)
                               .type(String.class)
                               .desc("websocket url")
                               .build();

    Options options = new Options();
    options.addOption(helpOption);
    options.addOption(debugOption);
    options.addOption(wsurlOption);

    CommandLineParser parser = new DefaultParser();
    CommandLine cmdLine = parser.parse(options, args);

    if (cmdLine.hasOption("help"))
    {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("mcpha-client", options);
    }
    else
    {
      debug = cmdLine.hasOption("debug");
      String wsurl = cmdLine.hasOption("wsurl") ?
        ((String)cmdLine.getParsedOptionValue("/wsurl")) : "mcpha";

//    staticFiles.externalLocation("/html5");
      staticFiles.location("/html5"); //index.html is served at localhost:4567 (default port)
      staticFiles.expireTime(600);
      webSocket(wsurl.startsWith("/")?wsurl:"/"+wsurl, WebsocketHandler.class);
      init();
    }
  }

  /**
   * 
   * @param message 
   */
  public static void logDebugMessage(String message)
  {
    if (debug)
    {
      System.out.println(message);
    }
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
    acquisitionUpdateTask = new AcquisitionUpdateTask(user, chan, 1000);
    
    // cancel any existing timer tasks.
    if (acquisitionMonitorTimer != null)
    {
      acquisitionMonitorTimer.cancel();
    }
    
    // create new timer task
    acquisitionMonitorTimer = new Timer();
    acquisitionMonitorTimer.schedule(acquisitionUpdateTask, 0);
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

    if (acquisitionUpdateTask != null)
    {
      acquisitionUpdateTask.exitLoop();
    }
    else
    {
      // stop data acquisition
      // device inactive
      mcphaSetAquisitionState(user, chan, 0L);
    }
    
    // cancel any existing timer tasks.
    if (acquisitionMonitorTimer != null)
    {
      acquisitionMonitorTimer.cancel();
    }
    
    // help out garbage collector
    acquisitionUpdateTask = null;
    acquisitionMonitorTimer = null;
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
    logDebugMessage("PUSH_MESSAGE:"+json);
    
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
      mcphaSetNegatorMode(0L, 0L);
      mcphaSetNegatorMode(1L, 0L);
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
   * @return timer value
   * @throws IOException 
   */
  synchronized public static double getHistogramData(Session user, long chan)
    throws IOException
  {
    // get elapsed time
    double t = mcphaGetTimerValue(chan);

    // get histogram data
    histogram_data = mcphaGetHistogramData(chan);

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
        
    sendJSONObjectMessage(user.getRemote(), json);

    // if ROI's have been defined then we push their data
    for (int i=1; i<=3; i++)
    {
      getRoiData(user, i);
    }
    
    return t;
  }

  /**
   * 
   * @param user
   * @param chan
   * @throws IOException 
   */
  synchronized public static void clearSpectrumData(Session user, long chan)
    throws IOException
  {
    mcphaResetHistogram(chan);

    // get histogram data
    getHistogramData(user, chan);
  }

  /**
   * 
   * @param user
   * @param roi
   * @throws java.io.IOException
   */
  synchronized public static void getRoiData(Session user, int roi)
    throws IOException
  {
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

      JSONObject json = new JSONObject();
      json.put("label", "ROI #"+roi);
      json.put("counts", counts);
      json.put("start", rois[roi-1].start);
      json.put("end", rois[roi-1].end);
      json.put("data", data);
      json.put("roi", roi);
      json.put("command", "get_roi_data");
      json.put("message", "");
      json.put("status", 0);
      
      sendJSONObjectMessage(user.getRemote(), json);
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
  synchronized public static void mcphaSetRoi(Session user, int roi, int start,
    int end)
    throws IOException
  {
    ROI r = null;
    
    if (roi < 1 || roi > 3)
    {
      JSONObject json = createJSONResponseObject();
      json.put("command", "set_roi");
      json.put("message", String.format("ROI number [%d] outside range of 1 to 3.", roi));
      json.put("status", 1);
      sendJSONObjectMessage( user.getRemote(), json);
    }
    
    rois[roi-1].start = start;
    rois[roi-1].end = end;
    rois[roi-1].counts = 0L;

    getRoiData(user, roi);
//    if (json != null)
//    {
//      JSONObject o = createJSONResponseObject();
//      o.put("command", "get_roi_data");
//      o.put("message", "");
//      o.put("roi"+roi, json);
//      o.put("status", 0);
//      sendJSONObjectMessage(user.getRemote(), o);
//    }
  }

  /**
   * 
   * @param user
   * @param channels
   * @param trigger_mode
   * @param trigger_level
   * @param trigger_slope
   * @param trigger_source
   * @throws IOException 
   */
  synchronized public static void acquireOscilloscopeData(Session user,
    int channels, String trigger_mode, int trigger_level, String trigger_slope,
    int trigger_source)
    throws IOException
  {
    System.out.println("ACQUIRE OSCILLOSCOPE");
    System.out.println("channels="+channels);
    System.out.println("trigger_level="+trigger_level);
    System.out.println("trigger_slope="+trigger_slope);
    System.out.println("trigger_source="+trigger_source);
    System.out.println("trigger_mode="+trigger_mode);
    
    
//    $controller command 2 0
//
//    set waiting 1
//
//    $controller command 19 0 5000
//    $controller command 20 0 65536
//    $controller command 21 0
    // Reset oscilloscope
    mcphaResetOscilloscope();
    
    try
    {
      Thread.sleep(1000);
    }
    catch (InterruptedException ex)
    {
      Logger.getLogger(Client.class.getName()).log(Level.SEVERE, null, ex);
    }
    
    // Set number of samples to skip before trigger
    mcphaSetNumberOfSamplesBeforeTrigger(5000);
    
    // Set total number of samples to acquire for this run
    mcphaSetTotalNumberOfSamplesToAcquire(65536);
    
    // Start oscilloscope
    mcphaStartOscilloscope();
    
    try
    {
      Thread.sleep(200);
    }
    catch (InterruptedException ex)
    {
      Logger.getLogger(Client.class.getName()).log(Level.SEVERE, null, ex);
    }
    
    // Read oscilloscope status
    for (int i=0; i<5; i++)
    {
      try
      {
        Thread.sleep(100);
      }
      catch (InterruptedException ex)
      {
        Logger.getLogger(Client.class.getName()).log(Level.SEVERE, null, ex);
      }
      System.out.println("Oscilloscope status:"+mcphaReadOscilloscopeStatus());
    }
    
    // get oscillsocope data
    ShortBuffer data = mcphaGetOsilloscopeData();
    
    boolean channel_1_requested = (channels & 0x01) != 0;
    boolean channel_2_requested = (channels & 0x02) != 0;
    
    // push data
    JSONObject json = createJSONResponseObject();
    json.put("command", "get_oscilloscope_data");
    json.put("message", "");
    json.put("status", 0);

    JSONArray arr1 = new JSONArray();
    JSONArray arr2 = new JSONArray();
    data.rewind();
    for (int i=0,n1=0,n2=0; i<data.capacity(); i+=2)
    {
      // channel 1 data
      JSONArray xy = new JSONArray();
      short d = data.get();
      if (channel_1_requested)
      {
        arr1.put(xy.put(n1++).put(d));
      }
      // channel 2 data
      xy = new JSONArray();
      d = data.get();
      if (channel_2_requested)
      {
        arr2.put(xy.put(n2++).put(d));
      }
    }
    json.put("data1", arr1);
    json.put("label1", "Channel 1");
    
    json.put("data2", arr2);
    json.put("label2", "Channel 2");
        
    sendJSONObjectMessage(user.getRemote(), json);

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
   * Set number of samples to skip before triggering.
   * 
   * @param samples
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetNumberOfSamplesBeforeTrigger(long samples)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_SET_NUMBER_OF_SAMPLES_BEFORE_TRIGGER, 0L, samples);
  }

  /**
   * Set total number of samples to acquire.
   * 
   * @param samples
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaSetTotalNumberOfSamplesToAcquire(long samples)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_SET_TOTAL_NUMBER_OF_SAMPLES_TO_ACQUIRE, 0L, samples);
  }

  /**
   * Start oscilloscope.
   * 
   * @throws java.io.IOException 
   */
  synchronized public static void mcphaStartOscilloscope()
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_START_OSCILLOSCOPE, 0L, 0L);
  }

  /**
   * Return the status of the oscilloscope.
   * 
   * @return the status of the oscilloscope
   * @throws java.io.IOException 
   */
  synchronized public static int mcphaReadOscilloscopeStatus()
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_READ_OSCILLOSCOPE_STATUS, 0L, 4L);
    
    // read response
    DataInputStream in = new DataInputStream(deviceSocket.getInputStream());
    
    return Integer.reverseBytes(in.readInt());
  }

  /**
   * Get histogram data
   * 
   * @param chan
   * @return 
   * @throws java.io.IOException 
   */
  synchronized public static IntBuffer mcphaGetHistogramData(long chan)
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_READ_HISTOGRAM_DATA, chan, 0);

    DataInputStream in = new DataInputStream(deviceSocket.getInputStream());
    
    ByteBuffer data = ByteBuffer.allocate(65536);
    data.order(ByteOrder.nativeOrder());
    in.readFully(data.array());

    return data.asIntBuffer();
  }

  /**
   * Get oscilloscope data which are 16-bit signed integer values.
   * The channels are interleaved sample-by-sample (ch1, ch2, ch1, ch2, etc).
   * 
   * @return a ShortBuffer of channel data values.
   * @throws java.io.IOException 
   */
  synchronized public static ShortBuffer mcphaGetOsilloscopeData()
    throws IOException
  {
    sendCommand(MCPHA_COMMAND_READ_OSCILLOSCOPE_DATA, 0L, 0L);

    DataInputStream in = new DataInputStream(deviceSocket.getInputStream());
    
    ByteBuffer data = ByteBuffer.allocate(65536);
    data.order(ByteOrder.nativeOrder());
    in.readFully(data.array());
    
    return data.asShortBuffer();
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
    logDebugMessage("sendCommand - code="+code+", chan="+chan+", data="+data);
    
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
