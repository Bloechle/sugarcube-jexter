/*
 * CVS identifier:
 *
 * $Id: DequantizerParams.java,v 1.16 2000/09/19 14:11:54 grosbois Exp $
 *
 * Class:                   DequantizerParams
 *
 * Description:             Generic class to hold dequantizer
 *                          parameters.
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 */
package jj2000.j2k.quantization.dequantizer;

/**
 * This is the generic ineterface for dequantization parameters. Generally, for each type of dequantizer, there should be a corresponding class to store its
 * parameters. The parameters are those that come from the bit stream header, that concern dequantization.
 *
 */
public abstract class DequantizerParams
{
  /**
   * Returns the type of the dequantizer for which the parameters are. The types are defined in the Dequantizer class.
   *
   * @return The type of the dequantizer for which the parameters are.
   *
   * @see Dequantizer
     *
   */
  public abstract int getDequantizerType();
}
