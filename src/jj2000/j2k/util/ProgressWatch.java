/*
 * CVS identifier:
 *
 * $Id: ProgressWatch.java,v 1.1 2002/05/22 16:19:28 grosbois Exp $
 *
 * Class:                   ProgressWatch
 *
 * Description: Interface defining methods for ProgressWatch objects.
 *
 * COPYRIGHT:
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.util;

public interface ProgressWatch
{
  /**
   * Initialize the progress watching process 
     *
   */
  public void initProgressWatch(int min, int max, String info);

  /**
   * Update the progress watching process to the specified value
     *
   */
  public void updateProgressWatch(int val, String info);

  /**
   * Terminate the progress watch process
     *
   */
  public void terminateProgressWatch();
}
