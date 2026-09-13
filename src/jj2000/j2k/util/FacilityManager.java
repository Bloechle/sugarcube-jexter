/*
 * CVS identifier:
 *
 * $Id: FacilityManager.java,v 1.12 2002/05/22 15:00:24 grosbois Exp $
 *
 * Class:                   MsgLoggerManager
 *
 * Description:             Manages common facilities across threads
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.util;

import java.util.Hashtable;

/**
 * This class manages common facilities for multi-threaded environments, It can register different facilities for each thread, and also a default one, so that
 * they can be referred by static methods, while possibly having different ones for different threads. Also a default facility exists that is used for threads
 * for which no particular facility has been registerd registered.
 *
 * <p>Currently the only kind of facilities managed is MsgLogger.</p>
 *
 * <P>An example use of this class is if 2 instances of a decoder are running in different threads and the messages of the 2 instances should be separated.
 *
 * <P>The default MsgLogger is a StreamMsgLogger that uses System.out as the 'out' stream and System.err as the 'err' stream, and a line width of 78. This can
 * be changed using the registerMsgLogger() method.
 *
 * @see MsgLogger
 * @see StreamMsgLogger
 *
 */
@SuppressWarnings({ "rawtypes", "unchecked", "cast", "this-escape", "serial", "static",
        "removal", "overrides", "lossy-conversions", "fallthrough", "deprecation" })   // vendored JJ2000: upstream code, kept as it is
public class FacilityManager
{
  /**
   * The loggers associated to different threads
   */
  private final static Hashtable loggerList = new Hashtable();
  /**
   * The default logger, for threads that have none associated with them
   */
  private static MsgLogger defMsgLogger =
    new StreamMsgLogger(System.out, System.err, 78);
  /**
   * The ProgressWatch instance associated to different threads
   */
  private final static Hashtable watchProgList = new Hashtable();
  /**
   * The default ProgressWatch for threads that have none associated with them.
   */
  private static ProgressWatch defWatchProg = null;

  /**
   *    */
  public static void registerProgressWatch(Thread t, ProgressWatch pw)
  {
    if (pw == null)
      throw new NullPointerException();
    if (t == null)
      defWatchProg = pw;
    else
      watchProgList.put(t, pw);
  }

  /**
   * Returns the ProgressWatch instance registered with the current thread (the thread that calls this method). If the current thread has no registered
   * ProgressWatch, then the default one is used. 
     *
   */
  public static ProgressWatch getProgressWatch()
  {
    ProgressWatch pw = (ProgressWatch) watchProgList.get(Thread.currentThread());
    return (pw == null) ? defWatchProg : pw;
  }

  /**
   * Registers the MsgLogger 'ml' as the logging facility of the thread 't'. If any other logging facility was registered with the thread 't' it is overriden by
   * 'ml'. If 't' is null then 'ml' is taken as the default message logger that is used for threads that have no MsgLogger registered.
   *
   * @param t The thread to associate with 'ml'
   *
   * @param ml The MsgLogger to associate with therad ml
     *
   */
  public static void registerMsgLogger(Thread t, MsgLogger ml)
  {
    if (ml == null)
      throw new NullPointerException();
    if (t == null)
      defMsgLogger = ml;
    else
      loggerList.put(t, ml);
  }

  /**
   * Returns the MsgLogger registered with the current thread (the thread that calls this method). If the current thread has no registered MsgLogger then the
   * default message logger is returned.
   *
   * @return The MsgLogger registerd for the current thread, or the default one if there is none registered for it.
     *
   */
  public static MsgLogger getMsgLogger()
  {
    MsgLogger ml =
      (MsgLogger) loggerList.get(Thread.currentThread());
    return (ml == null) ? defMsgLogger : ml;
  }

  /**
   * Returns the MsgLogger registered with the thread 't' (the thread that calls this method). If the thread 't' has no registered MsgLogger then the default
   * message logger is returned.
   *
   * @param t The thread for which to return the MsgLogger
   *
   * @return The MsgLogger registerd for the current thread, or the default one if there is none registered for it.
     *
   */
  public static MsgLogger getMsgLogger(Thread t)
  {
    MsgLogger ml =
      (MsgLogger) loggerList.get(t);
    return (ml == null) ? defMsgLogger : ml;
  }
}
