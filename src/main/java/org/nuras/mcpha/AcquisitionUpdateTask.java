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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.websocket.api.Session;

/**
 *
 * @author John Preston<byhisdeeds@gmail.com>
 */
public class AcquisitionUpdateTask extends TimerTask
{
  private final Session user;
  
  private final int chan;
  
  public AcquisitionUpdateTask(Session user, int chan)
  {
    this.user = user;
    this.chan = chan;
  }

  @Override
  public void run()
  {
    try
    {
      //Client.getHistogramData(user, chan);
      System.out.println("elapsed time:"+Client.mcphaGetTimerValue(chan));
    }
    catch (IOException ex)
    {
      Logger.getLogger(AcquisitionUpdateTask.class.getName()).log(Level.SEVERE, null, ex);
    }
  }
  
}
