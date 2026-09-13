/*
 * CVS identifier:
 *
 * $Id: StdDequantizerParams.java,v 1.9 2000/09/19 14:12:09 grosbois Exp $
 *
 * Class:                   StdDequantizerParams
 *
 * Description:             Parameters for the scalar deadzone dequantizers
 *
 *
 *
 * COPYRIGHT:
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 */
package jj2000.j2k.quantization.dequantizer;

import jj2000.j2k.quantization.QuantizationType;
import jj2000.j2k.wavelet.Subband;

/**
 * This class holds the parameters for the scalar deadzone dequantizer (StdDequantizer class) for the current tile. Its constructor decodes the parameters from
 * the main header and tile headers.
 *
 * @see StdDequantizer
 *
 */
public class StdDequantizerParams extends DequantizerParams
{
  /**
   * The quantization step "exponent" value, for each resolution level and subband, as it appears in the codestream. The first index is the resolution level,
   * and the second the subband index (within the resolution level), as specified in the Subband class. When in derived quantization mode only the first
   * resolution level (level 0) appears.
   *
   * <P>For non-reversible systems this value corresponds to ceil(log2(D')), where D' is the quantization step size normalized to data of a dynamic range of 1.
   * The true quantization step size is (2^R)*D', where R is ceil(log2(dr)), where 'dr' is the dynamic range of the subband samples, in the corresponding
   * subband.
   *
   * <P>For reversible systems the exponent value in 'exp' is used to determine the number of magnitude bits in the quantized coefficients. It is, in fact, the
   * dynamic range of the subband data.
   *
   * <P>In general the index of the first subband in a resolution level is not 0. The exponents appear, within each resolution level, at their subband index,
   * and not in the subband order starting from 0. For instance, resolution level 3, the first subband has the index 16, then the exponent of the subband is
   * exp[3][16], not exp[3][0].
   *
   * @see Subband
     *
   */
  public int exp[][];
  /**
   * The quantization step for non-reversible systems, normalized to a dynamic range of 1, for each resolution level and subband, as derived from the
   * exponent-mantissa representation in the codestream. The first index is the resolution level, and the second the subband index (within the resolution
   * level), as specified in the Subband class. When in derived quantization mode only the first resolution level (level 0) appears.
   *
   * <P>The true step size D is obtained as follows: D=(2^R)*D', where 'R=ceil(log2(dr))' and 'dr' is the dynamic range of the subband samples, in the
   * corresponding subband.
   *
   * <P>This value is 'null' for reversible systems (i.e. there is no true quantization, 'D' is always 1).
   *
   * <P>In general the index of the first subband in a resolution level is not 0. The steps appear, within each resolution level, at their subband index, and
   * not in the subband order starting from 0. For instance, if resolution level 3, the first subband has the index 16, then the step of the subband is
   * nStep[3][16], not nStep[3][0].
   *
   * @see Subband
     *
   */
  public float nStep[][];

  /**
   * Returns the type of the dequantizer for which the parameters are. The types are defined in the Dequantizer class.
   *
   * @return The type of the dequantizer for which the parameters are. Always Q_TYPE_SCALAR_DZ.
   *
   * @see Dequantizer
     *
   */
  public int getDequantizerType()
  {
    return QuantizationType.Q_TYPE_SCALAR_DZ;
  }
}
