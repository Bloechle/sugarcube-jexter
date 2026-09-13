/**
 * ***************************************************************************
 *
 * $Id: ICCProfileException.java,v 1.2 2002/08/08 14:08:13 grosbois Exp $
 *
 * Copyright Eastman Kodak Company, 343 State Street, Rochester, NY 14650 $Date $
 ****************************************************************************
 */
package jj2000.icc;

/**
 * This exception is thrown when the content of a profile is incorrect.
 *
 * @see	jj2000.j2k.icc.ICCProfile
 * @version	1.0
 * @author	Bruce A. Kern
 */
@SuppressWarnings({ "rawtypes", "unchecked", "cast", "this-escape", "serial", "static",
        "removal", "overrides", "lossy-conversions", "fallthrough", "deprecation" })   // vendored JJ2000: upstream code, kept as it is
public class ICCProfileException extends Exception
{
  /**
   * Contruct with message
   *
   * @param msg returned by getMessage()
   */
  public ICCProfileException(String msg)
  {
    super(msg);
  }

  /**
   * Empty constructor
   */
  public ICCProfileException()
  {
  }
}
