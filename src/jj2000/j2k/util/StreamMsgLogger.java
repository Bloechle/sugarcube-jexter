/*
 * CVS identifier:
 *
 * $Id: StreamMsgLogger.java,v 1.11 2000/09/05 09:25:30 grosbois Exp $
 *
 * Class:                   StreamMsgLogger
 *
 * Description:             Implementation of MsgLogger for streams
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * 
 * 
 * 
 */
package jj2000.j2k.util;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;

/**
 * This class implements the MsgLogger interface for streams. Streams can be simple files, terminals, stdout, stderr, etc. The messages or simple strings are
 * formatted using the linewidth given to the constructor.
 *
 * <P>Messages are printed to the 'err' stream if they are of severity WARNING or ERROR, otherwise they are printed to the 'out' stream. Simple strings are
 * always printed the 'out' stream.
 *
 */
public class StreamMsgLogger implements MsgLogger
{
  /**
   * The 'out' stream
   */
  private PrintWriter out;
  /**
   * The 'err' stream
   */
  private PrintWriter err;
  /**
   * The printer that formats the text
   */
  private MsgPrinter mp;

  /**
   * Constructs a StreamMsgLogger that uses 'outstr' as the 'out' stream, and 'errstr' as the 'err' stream. Note that 'outstr' and 'errstr' can be System.out
   * and System.err.
   *
   * @param outstr Where to print simple strings and LOG and INFO messages.
   *
   * @param errstr Where to print WARNING and ERROR messages
   *
   * @param lw The line width to use in formatting
   *
   *
   *
   */
  public StreamMsgLogger(OutputStream outstr, OutputStream errstr, int lw)
  {
    out = new PrintWriter(outstr, true);
    err = new PrintWriter(errstr, true);
    mp = new MsgPrinter(lw);
  }

  /**
   * Constructs a StreamMsgLogger that uses 'outstr' as the 'out' stream, and 'errstr' as the 'err' stream. Note that 'outstr' and 'errstr' can be System.out
   * and System.err.
   *
   * @param outstr Where to print simple strings and LOG and INFO messages.
   *
   * @param errstr Where to print WARNING and ERROR messages
   *
   * @param lw The line width to use in formatting
   *
   *
   *
   */
  public StreamMsgLogger(Writer outstr, Writer errstr, int lw)
  {
    out = new PrintWriter(outstr, true);
    err = new PrintWriter(errstr, true);
    mp = new MsgPrinter(lw);
  }

  /**
   * Constructs a StreamMsgLogger that uses 'outstr' as the 'out' stream, and 'errstr' as the 'err' stream. Note that 'outstr' and 'errstr' can be System.out
   * and System.err.
   *
   * @param outstr Where to print simple strings and LOG and INFO messages.
   *
   * @param errstr Where to print WARNING and ERROR messages
   *
   * @param lw The line width to use in formatting
   *
   *
   *
   */
  public StreamMsgLogger(PrintWriter outstr, PrintWriter errstr, int lw)
  {
    out = outstr;
    err = errstr;
    mp = new MsgPrinter(lw);
  }

  /**
   * Prints the message 'msg' to the output device, appending a newline, with severity 'sev'. The severity of the message is prepended to the message.
   *
   * @param sev The message severity (LOG, INFO, etc.)
   *
   * @param msg The message to display
   *
   *
   *
   */
  public void printmsg(int sev, String msg)
  {
    PrintWriter lout;
    int ind;
    String prefix;

    switch (sev)
    {
      case LOG:
        prefix = "[LOG]: ";
        lout = out;
        break;
      case INFO:
        prefix = "[INFO]: ";
        lout = out;
        break;
      case WARNING:
        prefix = "[WARNING]: ";
        lout = err;
        break;
      case ERROR:
        prefix = "[ERROR]: ";
        lout = err;
        break;
      default:
        throw new IllegalArgumentException("Severity " + sev + " not valid.");
    }

    mp.print(lout, 0, prefix.length(), prefix + msg);
    lout.flush();
  }

  /**
   * Prints the string 'str' to the 'out' stream, appending a newline. The message is reformatted to the line width given to the constructors and using 'flind'
   * characters to indent the first line and 'ind' characters to indent the second line. However, any newlines appearing in 'str' are respected. The output
   * device may or may not display the string until flush() is called, depending on the autoflush state of the PrintWriter, to be sure flush() should be called
   * to write the string to the device. This method just prints the string, the string does not make part of a "message" in the sense that noe severity is
   * associated to it.
   *
   * @param str The string to print
   *
   * @param flind Indentation of the first line
   *
   * @param ind Indentation of any other lines.
   *
   *
   *
   */
  public void println(String str, int flind, int ind)
  {
    mp.print(out, flind, ind, str);
  }

  /**
   * Writes any buffered data from the print() and println() methods to the device.
   *
   *
   *
   */
  public void flush()
  {
    out.flush();
  }
}
