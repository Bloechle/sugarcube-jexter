/*
 * CVS identifier:
 *
 * $Id: MsgLogger.java,v 1.7 2001/08/17 16:24:51 grosbois Exp $
 *
 * Class:                   MsgLogger
 *
 * Description:             Facility to log messages (abstract)
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.util;

/**
 * This class provides a simple common abstraction of a facility that logs and/or displays messages or simple strings. The underlying facility can be a
 * terminal, text file, text area in a GUI display, dialog boxes in a GUI display, etc., or a combination of those.
 *
 * <>Messages are short strings (a couple of lines) that indicate some state of the program, and that have a severity code associated with them (see below).
 * Simple strings is text (can be long) that has no severity code associated with it. Typical use of simple strings is to display help texts.</p>
 *
 * <p>Each message has a severity code, which can be one of the following: LOG, INFO, WARNING, ERROR. Each implementation should treat each severity code in a
 * way which corresponds to the type of diplay used.</p>
 *
 * <p>Messages are printed via the 'printmsg()' method. Simple strings are printed via the 'print()', 'println()' and 'flush()' methods, each simple string is
 * considered to be terminated once the 'flush()' method has been called. The 'printmsg()' method should never be called before a previous simple string has
 * been terminated.</p>
 *
 */
public interface MsgLogger
{
  /**
   * Severity of message. LOG messages are just for bookkeeping and do not need to be displayed in the majority of cases
   */
  public static final int LOG = 0;
  /**
   * Severity of message. INFO messages should be displayed just for user feedback.
   */
  public static final int INFO = 1;
  /**
   * Severity of message. WARNING messages denote that an unexpected state has been reached and should be given as feedback to the user.
   */
  public static final int WARNING = 2;
  /**
   * Severity of message. ERROR messages denote that something has gone wrong and probably that execution has ended. They should be definetely displayed to the
   * user.
   */
  public static final int ERROR = 3;

  /**
   * Prints the message 'msg' to the output device, appending a newline, with severity 'sev'. Some implementations where the appended newline is irrelevant may
   * not append the newline. Depending on the implementation the severity of the message may be added to it. The message is reformatted as appropriate for the
   * output devic, but any newline characters are respected.
   *
   * @param sev The message severity (LOG, INFO, etc.)
   *
   * @param msg The message to display
     *
   */
  public void printmsg(int sev, String msg);

  /**
   * Prints the string 'str' to the output device, appending a line return. The message is reformatted as appropriate to the particular diplaying device, where
   * 'flind' and 'ind' are used as hints for performing that operation. However, any newlines appearing in 'str' are respected. The output device may not
   * display the string until flush() is called. Some implementations may automatically flush when this method is called. This method just prints the string,
   * the string does not make part of a "message" in the sense that no severity is associated to it.
   *
   * @param str The string to print
   *
   * @param flind Indentation of the first line
   *
   * @param ind Indentation of any other lines.
     *
   */
  public void println(String str, int flind, int ind);

  /**
   * Writes any buffered data from the println() method to the device.
     *
   */
  public void flush();
}
