/*
 * CVS identifier:
 *
 * $Id: StringFormatException.java,v 1.9 2000/09/05 09:25:31 grosbois Exp $
 *
 * Class:                   ArgumentFormatException
 *
 * Description:             Exception for badly formatted string
 *                          argument exceptions.
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

/**
 * Thrown to indicate that the application has attempted to parse a badly formatted string.
 *
 */
@SuppressWarnings({ "rawtypes", "unchecked", "cast", "this-escape", "serial", "static",
        "removal", "overrides", "lossy-conversions", "fallthrough", "deprecation" })   // vendored JJ2000: upstream code, kept as it is
public class StringFormatException extends IllegalArgumentException
{
  /**
   * Creates the exception with an empty messgage.
   *
   *
   *
   */
  public StringFormatException()
  {
    super();
  }

  /**
   * Creates the exception with the specified detail message.
   *
   * @param s The detail message
   *
   *
   *
   */
  public StringFormatException(String s)
  {
    super(s);
  }
}
