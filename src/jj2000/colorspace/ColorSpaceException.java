/**
 * ***************************************************************************
 *
 * $Id: ColorSpaceException.java,v 1.1 2002/07/25 14:52:00 grosbois Exp $
 *
 * Copyright Eastman Kodak Company, 343 State Street, Rochester, NY 14650 $Date $
 ****************************************************************************
 */
package jj2000.colorspace;

/**
 * This exception is thrown when the content of an image contains an incorrect colorspace box
 *
 * @see	jj2000.j2k.colorspace.ColorSpaceMapper
 * @version	1.0
 * @author	Bruce A. Kern
 */
@SuppressWarnings({ "rawtypes", "unchecked", "cast", "this-escape", "serial", "static",
        "removal", "overrides", "lossy-conversions", "fallthrough", "deprecation" })   // vendored JJ2000: upstream code, kept as it is
public class ColorSpaceException extends Exception
{
  /**
   * Contruct with message
   *
   * @param msg returned by getMessage()
   */
  public ColorSpaceException(String msg)
  {
    super(msg);
  }

  /**
   * Empty constructor
   */
  public ColorSpaceException()
  {
  }
  /* end class ColorSpaceException */ }
