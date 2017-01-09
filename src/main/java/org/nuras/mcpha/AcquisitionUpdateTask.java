/*
 * Copyright 2017 John Preston<byhisdeeds@gmail.com> NURAS.
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

import java.io.IOException;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.websocket.api.Session;

/**
 *
 * @author John Preston<byhisdeeds@gmail.com>
 */
public class AcquisitionUpdateTask extends TimerTask
{
  private final AtomicBoolean again = new AtomicBoolean(false);
  
  private final Session user;
  
  private final int chan;
  
  private final long delay;
  
  /**
   * 
   * @param user
   * @param chan
   * @param delay number of milliseconds between loops
   */
  public AcquisitionUpdateTask(Session user, int chan, int delay)
  {
    this.user = user;
    this.chan = chan;
    this.delay = delay;
  }

  /**
   * Run method that returns when the return value from the 
   * get timer value function is the same for 2 consecutive calls.
   * 
   */
  @Override
  public void run()
  {
    double t0 = -1.0;
    again.set(true);
    
    do
    {
      try
      {
        double t = Client.getHistogramData(user, chan);
        if (t0 == t)
        {
          again.set(false);
        }
        else
        {
          t0 = t;
        }
        
        try
        {
          Thread.sleep(delay);
        }
        catch (InterruptedException ex)
        {
          Logger.getLogger(AcquisitionUpdateTask.class.getName()).log(Level.SEVERE, null, ex);
        }
      }
      catch (IOException ex)
      {
        Logger.getLogger(AcquisitionUpdateTask.class.getName()).log(Level.SEVERE, null, ex);
        t0 = -1.0;
      }
    } while (again.get());
    
    try
    {
      // signal end of acquisition. We should only reach this point
      // when the acquisition is completed abd successive get_timer_value
      // calls return the same value. or we have been told to stop acquisition
      Client.mcphaSetAquisitionState(user, chan, 0L);
    }
    catch (IOException ex)
    {
      Logger.getLogger(AcquisitionUpdateTask.class.getName()).log(Level.SEVERE, null, ex);
    }
  }
  
  /**
   * 
   */
  public void exitLoop()
  {
    again.set(false);
  }
}
