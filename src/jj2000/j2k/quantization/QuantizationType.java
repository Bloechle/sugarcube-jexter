/*
 * CVS identifier:
 *
 * $Id: QuantizationType.java,v 1.10 2000/09/19 14:11:30 grosbois Exp $
 *
 * Class:                   QuantizationType
 *
 * Description:             This interface defines the possible
 *                          quantization types.
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
package jj2000.j2k.quantization;

/**
 * This interface defines the IDs of the possible quantization types. JPEG 2000 part I support only the scalar quantization with dead zone. However other
 * quantization type may be defined in JPEG 2000 extensions (for instance Trellis Coded Quantization).
 *
 * <P>This interface defines the constants only. In order to use the constants in any other class you can either use the fully qualified name (e.g.,
 * <tt>QuantizationType.Q_TYPE_SCALAR_DZ</tt>) or declare this interface in the implements clause of the class and then access the identifier directly.
 *
 */
public interface QuantizationType
{
  /**
   * The ID of the scalar deadzone dequantizer
   */
  public final static int Q_TYPE_SCALAR_DZ = 0;
}
