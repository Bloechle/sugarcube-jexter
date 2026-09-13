/**
 * ***************************************************************************
 *
 * $Id: MatrixBasedTransformException.java,v 1.1 2002/07/25 14:56:49 grosbois Exp $
 *
 * Copyright Eastman Kodak Company, 343 State Street, Rochester, NY 14650 $Date $
 ****************************************************************************
 */
package jj2000.icc.lut;

/**
 * Thrown by MatrixBasedTransformTosRGB
 *
 * @see jj2000.j2k.icc.lut.MatrixBasedTransformTosRGB
 * @version	1.0
 * @author	Bruce A. Kern
 */
@SuppressWarnings({ "rawtypes", "unchecked", "cast", "this-escape", "serial", "static",
        "removal", "overrides", "lossy-conversions", "fallthrough", "deprecation" })   // vendored JJ2000: upstream code, kept as it is
public class MatrixBasedTransformException extends Exception
{
  /**
   * Contruct with message
   *
   * @param msg returned by getMessage()
   */
  MatrixBasedTransformException(String msg)
  {
    super(msg);
  }

  /**
   * Empty constructor
   */
  MatrixBasedTransformException()
  {
  }
  /* end class MatrixBasedTransformException */ }
